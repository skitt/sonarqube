/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.phases;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.resources.Project;
import org.sonar.batch.bootstrap.AnalysisMode;
import org.sonar.batch.events.BatchStepEvent;
import org.sonar.batch.events.EventBus;
import org.sonar.batch.index.DefaultIndex;
import org.sonar.batch.index.PersistenceManager;
import org.sonar.batch.index.ScanPersister;
import org.sonar.batch.issue.ignore.scanner.IssueExclusionsLoader;
import org.sonar.batch.rule.QProfileVerifier;
import org.sonar.batch.scan.filesystem.DefaultModuleFileSystem;
import org.sonar.batch.scan.filesystem.FileSystemLogger;
import org.sonar.batch.scan.maven.MavenPluginsConfigurator;
import org.sonar.batch.scan.report.JsonReport;

import java.util.Collection;

public final class PhaseExecutor {

  public static final Logger LOGGER = LoggerFactory.getLogger(PhaseExecutor.class);

  private final EventBus eventBus;
  private final Phases phases;
  private final DecoratorsExecutor decoratorsExecutor;
  private final MavenPluginsConfigurator mavenPluginsConfigurator;
  private final PostJobsExecutor postJobsExecutor;
  private final InitializersExecutor initializersExecutor;
  private final SensorsExecutor sensorsExecutor;
  private final UpdateStatusJob updateStatusJob;
  private final PersistenceManager persistenceManager;
  private final SensorContext sensorContext;
  private final DefaultIndex index;
  private final ProjectInitializer pi;
  private final ScanPersister[] persisters;
  private final FileSystemLogger fsLogger;
  private final JsonReport jsonReport;
  private final DefaultModuleFileSystem fs;
  private final QProfileVerifier profileVerifier;
  private final IssueExclusionsLoader issueExclusionsLoader;
  private final AnalysisMode analysisMode;

  public PhaseExecutor(Phases phases, DecoratorsExecutor decoratorsExecutor,
    MavenPluginsConfigurator mavenPluginsConfigurator, InitializersExecutor initializersExecutor,
    PostJobsExecutor postJobsExecutor, SensorsExecutor sensorsExecutor,
    PersistenceManager persistenceManager, SensorContext sensorContext, DefaultIndex index,
    EventBus eventBus, UpdateStatusJob updateStatusJob, ProjectInitializer pi,
    ScanPersister[] persisters, FileSystemLogger fsLogger, JsonReport jsonReport, DefaultModuleFileSystem fs, QProfileVerifier profileVerifier,
    IssueExclusionsLoader issueExclusionsLoader, AnalysisMode analysisMode) {
    this.phases = phases;
    this.decoratorsExecutor = decoratorsExecutor;
    this.mavenPluginsConfigurator = mavenPluginsConfigurator;
    this.postJobsExecutor = postJobsExecutor;
    this.initializersExecutor = initializersExecutor;
    this.sensorsExecutor = sensorsExecutor;
    this.persistenceManager = persistenceManager;
    this.sensorContext = sensorContext;
    this.index = index;
    this.eventBus = eventBus;
    this.updateStatusJob = updateStatusJob;
    this.pi = pi;
    this.persisters = persisters;
    this.fsLogger = fsLogger;
    this.jsonReport = jsonReport;
    this.fs = fs;
    this.profileVerifier = profileVerifier;
    this.issueExclusionsLoader = issueExclusionsLoader;
    this.analysisMode = analysisMode;
  }

  public static Collection<Class> getPhaseClasses() {
    return Lists.<Class>newArrayList(DecoratorsExecutor.class, MavenPluginsConfigurator.class,
      PostJobsExecutor.class, SensorsExecutor.class,
      InitializersExecutor.class, ProjectInitializer.class, UpdateStatusJob.class);
  }

  /**
   * Executed on each module
   */
  public void execute(Project module) {
    pi.execute(module);

    eventBus.fireEvent(new ProjectAnalysisEvent(module, true));

    executeMavenPhase(module);

    executeInitializersPhase();

    if (phases.isEnabled(Phases.Phase.SENSOR)) {
      // Index and lock the filesystem
      indexFs();

      // Log detected languages and their profiles after FS is indexed and languages detected
      profileVerifier.execute();

      // Initialize issue exclusions
      initIssueExclusions();

      sensorsExecutor.execute(sensorContext);
    }

    if (phases.isEnabled(Phases.Phase.DECORATOR)) {
      decoratorsExecutor.execute();
    }

    if (module.isRoot()) {
      jsonReport.execute();

      executePersisters();
      updateStatusJob();
      if (phases.isEnabled(Phases.Phase.POSTJOB)) {
        postJobsExecutor.execute(sensorContext);
      }
    }
    cleanMemory();
    eventBus.fireEvent(new ProjectAnalysisEvent(module, false));
  }

  private void initIssueExclusions() {
    String stepName = "Init issue exclusions";
    eventBus.fireEvent(new BatchStepEvent(stepName, true));
    issueExclusionsLoader.execute();
    eventBus.fireEvent(new BatchStepEvent(stepName, false));
  }

  private void indexFs() {
    String stepName = "Index filesystem and store sources";
    eventBus.fireEvent(new BatchStepEvent(stepName, true));
    fs.index();
    eventBus.fireEvent(new BatchStepEvent(stepName, false));
  }

  private void executePersisters() {
    if (!analysisMode.isPreview()) {
      LOGGER.info("Store results in database");
      eventBus.fireEvent(new PersistersPhaseEvent(Lists.newArrayList(persisters), true));
      for (ScanPersister persister : persisters) {
        LOGGER.debug("Execute {}", persister.getClass().getName());
        eventBus.fireEvent(new PersisterExecutionEvent(persister, true));
        persister.persist();
        eventBus.fireEvent(new PersisterExecutionEvent(persister, false));
      }

      eventBus.fireEvent(new PersistersPhaseEvent(Lists.newArrayList(persisters), false));
    }
  }

  private void updateStatusJob() {
    if (updateStatusJob != null) {
      String stepName = "Update status job";
      eventBus.fireEvent(new BatchStepEvent(stepName, true));
      this.updateStatusJob.execute();
      eventBus.fireEvent(new BatchStepEvent(stepName, false));
    }
  }

  private void executeInitializersPhase() {
    if (phases.isEnabled(Phases.Phase.INIT)) {
      initializersExecutor.execute();
      fsLogger.log();
    }
  }

  private void executeMavenPhase(Project module) {
    if (phases.isEnabled(Phases.Phase.MAVEN)) {
      eventBus.fireEvent(new MavenPhaseEvent(true));
      mavenPluginsConfigurator.execute(module);
      eventBus.fireEvent(new MavenPhaseEvent(false));
    }
  }

  private void cleanMemory() {
    String cleanMemory = "Clean memory";
    eventBus.fireEvent(new BatchStepEvent(cleanMemory, true));
    persistenceManager.clear();
    index.clear();
    eventBus.fireEvent(new BatchStepEvent(cleanMemory, false));
  }
}
