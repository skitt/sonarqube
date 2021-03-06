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
package org.sonar.batch.mediumtest;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.SonarPlugin;
import org.sonar.api.batch.bootstrap.ProjectReactor;
import org.sonar.api.batch.debt.internal.DefaultDebtModel;
import org.sonar.api.batch.fs.InputDir;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputPath;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.sensor.dependency.Dependency;
import org.sonar.api.batch.sensor.duplication.DuplicationGroup;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.batch.sensor.measure.Measure;
import org.sonar.api.batch.sensor.measure.internal.DefaultMeasure;
import org.sonar.api.batch.sensor.symbol.Symbol;
import org.sonar.api.batch.sensor.test.TestCaseCoverage;
import org.sonar.api.batch.sensor.test.TestCaseExecution;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.platform.PluginMetadata;
import org.sonar.batch.bootstrap.PluginsReferential;
import org.sonar.batch.bootstrap.TaskProperties;
import org.sonar.batch.bootstrapper.Batch;
import org.sonar.batch.bootstrapper.EnvironmentInformation;
import org.sonar.batch.dependency.DependencyCache;
import org.sonar.batch.duplication.DuplicationCache;
import org.sonar.batch.highlighting.SyntaxHighlightingData;
import org.sonar.batch.highlighting.SyntaxHighlightingRule;
import org.sonar.batch.index.Cache.Entry;
import org.sonar.batch.index.ComponentDataCache;
import org.sonar.batch.protocol.input.ActiveRule;
import org.sonar.batch.protocol.input.GlobalReferentials;
import org.sonar.batch.protocol.input.ProjectReferentials;
import org.sonar.batch.referential.GlobalReferentialsLoader;
import org.sonar.batch.referential.ProjectReferentialsLoader;
import org.sonar.batch.scan.filesystem.InputPathCache;
import org.sonar.batch.scan2.IssueCache;
import org.sonar.batch.scan2.MeasureCache;
import org.sonar.batch.scan2.ProjectScanContainer;
import org.sonar.batch.scan2.ScanTaskObserver;
import org.sonar.batch.symbol.SymbolData;
import org.sonar.batch.test.TestCaseCoverageCache;
import org.sonar.batch.test.TestCaseExecutionCache;
import org.sonar.core.plugins.DefaultPluginMetadata;
import org.sonar.core.plugins.RemotePlugin;
import org.sonar.core.source.SnapshotDataTypes;

import javax.annotation.CheckForNull;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Main utility class for writing batch medium tests.
 * 
 */
public class BatchMediumTester {

  private Batch batch;

  public static BatchMediumTesterBuilder builder() {
    return new BatchMediumTesterBuilder().registerCoreMetrics();
  }

  public static class BatchMediumTesterBuilder {
    private final FakeGlobalReferentialsLoader globalRefProvider = new FakeGlobalReferentialsLoader();
    private final FakeProjectReferentialsLoader projectRefProvider = new FakeProjectReferentialsLoader();
    private final FakePluginsReferential pluginsReferential = new FakePluginsReferential();
    private final Map<String, String> bootstrapProperties = new HashMap<String, String>();

    public BatchMediumTester build() {
      return new BatchMediumTester(this);
    }

    public BatchMediumTesterBuilder registerPlugin(String pluginKey, File location) {
      pluginsReferential.addPlugin(pluginKey, location);
      return this;
    }

    public BatchMediumTesterBuilder registerPlugin(String pluginKey, SonarPlugin instance) {
      pluginsReferential.addPlugin(pluginKey, instance);
      return this;
    }

    public BatchMediumTesterBuilder registerCoreMetrics() {
      for (Metric<?> m : CoreMetrics.getMetrics()) {
        registerMetric(m);
      }
      return this;
    }

    public BatchMediumTesterBuilder registerMetric(Metric<?> metric) {
      globalRefProvider.add(metric);
      return this;
    }

    public BatchMediumTesterBuilder addQProfile(String language, String name) {
      projectRefProvider.addQProfile(language, name);
      return this;
    }

    public BatchMediumTesterBuilder addDefaultQProfile(String language, String name) {
      addQProfile(language, name);
      globalRefProvider.globalSettings().put("sonar.profile." + language, name);
      return this;
    }

    public BatchMediumTesterBuilder bootstrapProperties(Map<String, String> props) {
      bootstrapProperties.putAll(props);
      return this;
    }

    public BatchMediumTesterBuilder activateRule(ActiveRule activeRule) {
      projectRefProvider.addActiveRule(activeRule);
      return this;
    }

  }

  public void start() {
    batch.start();
  }

  public void stop() {
    batch.stop();
  }

  private BatchMediumTester(BatchMediumTesterBuilder builder) {
    batch = Batch.builder()
      .setEnableLoggingConfiguration(true)
      .addComponents(
        new EnvironmentInformation("mediumTest", "1.0"),
        builder.pluginsReferential,
        builder.globalRefProvider,
        builder.projectRefProvider,
        new DefaultDebtModel())
      .setBootstrapProperties(builder.bootstrapProperties)
      .build();
  }

  public TaskBuilder newTask() {
    return new TaskBuilder(this);
  }

  public TaskBuilder newScanTask(File sonarProps) {
    Properties prop = new Properties();
    FileReader reader = null;
    try {
      reader = new FileReader(sonarProps);
      prop.load(reader);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to read configuration file", e);
    } finally {
      if (reader != null) {
        IOUtils.closeQuietly(reader);
      }
    }
    TaskBuilder builder = new TaskBuilder(this);
    builder.property("sonar.task", "scan");
    builder.property("sonar.projectBaseDir", sonarProps.getParentFile().getAbsolutePath());
    for (Map.Entry entry : prop.entrySet()) {
      builder.property(entry.getKey().toString(), entry.getValue().toString());
    }
    return builder;
  }

  public static class TaskBuilder {
    private final Map<String, String> taskProperties = new HashMap<String, String>();
    private BatchMediumTester tester;

    public TaskBuilder(BatchMediumTester tester) {
      this.tester = tester;
    }

    public TaskResult start() {
      TaskResult result = new TaskResult();
      tester.batch.executeTask(taskProperties,
        result
        );
      return result;
    }

    public TaskBuilder properties(Map<String, String> props) {
      taskProperties.putAll(props);
      return this;
    }

    public TaskBuilder property(String key, String value) {
      taskProperties.put(key, value);
      return this;
    }
  }

  public static class TaskResult implements ScanTaskObserver {

    private static final Logger LOG = LoggerFactory.getLogger(BatchMediumTester.TaskResult.class);

    private List<Issue> issues = new ArrayList<Issue>();
    private List<Measure> measures = new ArrayList<Measure>();
    private Map<String, List<DuplicationGroup>> duplications = new HashMap<String, List<DuplicationGroup>>();
    private Map<String, InputFile> inputFiles = new HashMap<String, InputFile>();
    private Map<String, InputDir> inputDirs = new HashMap<String, InputDir>();
    private Map<InputFile, SyntaxHighlightingData> highlightingPerFile = new HashMap<InputFile, SyntaxHighlightingData>();
    private Map<InputFile, SymbolData> symbolTablePerFile = new HashMap<InputFile, SymbolData>();
    private Map<String, Map<String, TestCaseExecution>> testCasesPerFile = new HashMap<String, Map<String, TestCaseExecution>>();
    private Map<String, Map<String, Map<String, List<Integer>>>> coveragePerTest = new HashMap<String, Map<String, Map<String, List<Integer>>>>();
    private Map<String, Map<String, Integer>> dependencies = new HashMap<String, Map<String, Integer>>();

    @Override
    public void scanTaskCompleted(ProjectScanContainer container) {
      LOG.info("Store analysis results in memory for later assertions in medium test");
      for (Issue issue : container.getComponentByType(IssueCache.class).all()) {
        issues.add(issue);
      }

      for (DefaultMeasure<?> measure : container.getComponentByType(MeasureCache.class).all()) {
        measures.add(measure);
      }

      storeFs(container);
      storeComponentData(container);
      storeDuplication(container);
      storeTestCases(container);
      storeCoveragePerTest(container);
      storeDependencies(container);

    }

    private void storeCoveragePerTest(ProjectScanContainer container) {
      TestCaseCoverageCache testCaseCoverageCache = container.getComponentByType(TestCaseCoverageCache.class);
      for (Entry<TestCaseCoverage> entry : testCaseCoverageCache.entries()) {
        String testFileKey = entry.key()[0].toString();
        if (!coveragePerTest.containsKey(testFileKey)) {
          coveragePerTest.put(testFileKey, new HashMap<String, Map<String, List<Integer>>>());
        }
        String testName = entry.key()[1].toString();
        if (!coveragePerTest.get(testFileKey).containsKey(testName)) {
          coveragePerTest.get(testFileKey).put(testName, new HashMap<String, List<Integer>>());
        }
        TestCaseCoverage value = entry.value();
        coveragePerTest.get(testFileKey).get(testName).put(entry.key()[2].toString(), value != null ? value.coveredLines() : null);
      }
    }

    private void storeTestCases(ProjectScanContainer container) {
      TestCaseExecutionCache testCaseCache = container.getComponentByType(TestCaseExecutionCache.class);
      for (Entry<TestCaseExecution> entry : testCaseCache.entries()) {
        String effectiveKey = entry.key()[0].toString();
        if (!testCasesPerFile.containsKey(effectiveKey)) {
          testCasesPerFile.put(effectiveKey, new HashMap<String, TestCaseExecution>());
        }
        testCasesPerFile.get(effectiveKey).put(entry.value().name(), entry.value());
      }
    }

    private void storeDuplication(ProjectScanContainer container) {
      DuplicationCache duplicationCache = container.getComponentByType(DuplicationCache.class);
      for (Entry<List<DuplicationGroup>> entry : duplicationCache.entries()) {
        String effectiveKey = entry.key()[0].toString();
        duplications.put(effectiveKey, entry.value());
      }
    }

    private void storeComponentData(ProjectScanContainer container) {
      ComponentDataCache componentDataCache = container.getComponentByType(ComponentDataCache.class);
      for (InputFile file : inputFiles.values()) {
        SyntaxHighlightingData highlighting = componentDataCache.getData(((DefaultInputFile) file).key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING);
        if (highlighting != null) {
          highlightingPerFile.put(file, highlighting);
        }
        SymbolData symbolTable = componentDataCache.getData(((DefaultInputFile) file).key(), SnapshotDataTypes.SYMBOL_HIGHLIGHTING);
        if (symbolTable != null) {
          symbolTablePerFile.put(file, symbolTable);
        }
      }
    }

    private void storeFs(ProjectScanContainer container) {
      InputPathCache inputFileCache = container.getComponentByType(InputPathCache.class);
      for (InputPath inputPath : inputFileCache.all()) {
        if (inputPath instanceof InputFile) {
          inputFiles.put(inputPath.relativePath(), (InputFile) inputPath);
        } else {
          inputDirs.put(inputPath.relativePath(), (InputDir) inputPath);
        }
      }
    }

    private void storeDependencies(ProjectScanContainer container) {
      DependencyCache dependencyCache = container.getComponentByType(DependencyCache.class);
      for (Entry<Dependency> entry : dependencyCache.entries()) {
        String fromKey = entry.key()[1].toString();
        String toKey = entry.key()[2].toString();
        if (!dependencies.containsKey(fromKey)) {
          dependencies.put(fromKey, new HashMap<String, Integer>());
        }
        dependencies.get(fromKey).put(toKey, entry.value().weight());
      }
    }

    public List<Issue> issues() {
      return issues;
    }

    public List<Measure> measures() {
      return measures;
    }

    public Collection<InputFile> inputFiles() {
      return inputFiles.values();
    }

    @CheckForNull
    public InputFile inputFile(String relativePath) {
      return inputFiles.get(relativePath);
    }

    public Collection<InputDir> inputDirs() {
      return inputDirs.values();
    }

    @CheckForNull
    public InputDir inputDir(String relativePath) {
      return inputDirs.get(relativePath);
    }

    public List<DuplicationGroup> duplicationsFor(InputFile inputFile) {
      return duplications.get(((DefaultInputFile) inputFile).key());
    }

    public Collection<TestCaseExecution> testCasesFor(InputFile inputFile) {
      String key = ((DefaultInputFile) inputFile).key();
      if (testCasesPerFile.containsKey(key)) {
        return testCasesPerFile.get(key).values();
      } else {
        return Collections.emptyList();
      }
    }

    public TestCaseExecution testCase(InputFile inputFile, String testCaseName) {
      return testCasesPerFile.get(((DefaultInputFile) inputFile).key()).get(testCaseName);
    }

    public List<Integer> coveragePerTest(InputFile testFile, String testCaseName, InputFile mainFile) {
      String testKey = ((DefaultInputFile) testFile).key();
      String mainKey = ((DefaultInputFile) mainFile).key();
      if (coveragePerTest.containsKey(testKey) && coveragePerTest.get(testKey).containsKey(testCaseName) && coveragePerTest.get(testKey).get(testCaseName).containsKey(mainKey)) {
        return coveragePerTest.get(testKey).get(testCaseName).get(mainKey);
      } else {
        return Collections.emptyList();
      }
    }

    /**
     * Get highlighting types at a given position in an inputfile
     * @param charIndex 0-based offset in file
     */
    public List<TypeOfText> highlightingTypeFor(InputFile file, int charIndex) {
      SyntaxHighlightingData syntaxHighlightingData = highlightingPerFile.get(file);
      if (syntaxHighlightingData == null) {
        return Collections.emptyList();
      }
      List<TypeOfText> result = new ArrayList<TypeOfText>();
      for (SyntaxHighlightingRule sortedRule : syntaxHighlightingData.syntaxHighlightingRuleSet()) {
        if (sortedRule.getStartPosition() <= charIndex && sortedRule.getEndPosition() > charIndex) {
          result.add(sortedRule.getTextType());
        }
      }
      return result;
    }

    /**
     * Get list of all positions of a symbol in an inputfile
     * @param symbolStartOffset 0-based start offset for the symbol in file
     * @param symbolEndOffset 0-based end offset for the symbol in file
     */
    @CheckForNull
    public List<Integer> symbolReferencesFor(InputFile file, int symbolStartOffset, int symbolEndOffset) {
      SymbolData data = symbolTablePerFile.get(file);
      if (data == null) {
        return null;
      }
      for (Symbol symbol : data.referencesBySymbol().keySet()) {
        if (symbol.getDeclarationStartOffset() == symbolStartOffset && symbol.getDeclarationEndOffset() == symbolEndOffset) {
          return data.referencesBySymbol().get(symbol);
        }
      }
      return null;
    }

    /**
     * @return null if no dependency else return dependency weight.
     */
    @CheckForNull
    public Integer dependencyWeight(InputFile from, InputFile to) {
      String fromKey = ((DefaultInputFile) from).key();
      String toKey = ((DefaultInputFile) to).key();
      return dependencies.containsKey(fromKey) ? dependencies.get(fromKey).get(toKey) : null;
    }
  }

  private static class FakeGlobalReferentialsLoader implements GlobalReferentialsLoader {

    private int metricId = 1;

    private GlobalReferentials ref = new GlobalReferentials();

    @Override
    public GlobalReferentials load() {
      return ref;
    }

    public Map<String, String> globalSettings() {
      return ref.globalSettings();
    }

    public FakeGlobalReferentialsLoader add(Metric metric) {
      Boolean optimizedBestValue = metric.isOptimizedBestValue();
      ref.metrics().add(new org.sonar.batch.protocol.input.Metric(metricId,
        metric.key(),
        metric.getType().name(),
        metric.getDescription(),
        metric.getDirection(),
        metric.getName(),
        metric.getQualitative(),
        metric.getUserManaged(),
        metric.getWorstValue(),
        metric.getBestValue(),
        optimizedBestValue != null ? optimizedBestValue : false));
      metricId++;
      return this;
    }
  }

  private static class FakeProjectReferentialsLoader implements ProjectReferentialsLoader {

    private ProjectReferentials ref = new ProjectReferentials();

    @Override
    public ProjectReferentials load(ProjectReactor reactor, TaskProperties taskProperties) {
      return ref;
    }

    public FakeProjectReferentialsLoader addQProfile(String language, String name) {
      ref.addQProfile(new org.sonar.batch.protocol.input.QProfile(name, name, language, new Date()));
      return this;
    }

    public FakeProjectReferentialsLoader addActiveRule(ActiveRule activeRule) {
      ref.addActiveRule(activeRule);
      return this;
    }
  }

  private static class FakePluginsReferential implements PluginsReferential {

    private List<RemotePlugin> pluginList = new ArrayList<RemotePlugin>();
    private Map<RemotePlugin, File> pluginFiles = new HashMap<RemotePlugin, File>();
    Map<PluginMetadata, SonarPlugin> localPlugins = new HashMap<PluginMetadata, SonarPlugin>();

    @Override
    public List<RemotePlugin> pluginList() {
      return pluginList;
    }

    @Override
    public File pluginFile(RemotePlugin remote) {
      return pluginFiles.get(remote);
    }

    public FakePluginsReferential addPlugin(String pluginKey, File location) {
      RemotePlugin plugin = new RemotePlugin(pluginKey, false);
      pluginList.add(plugin);
      pluginFiles.put(plugin, location);
      return this;
    }

    public FakePluginsReferential addPlugin(String pluginKey, SonarPlugin pluginInstance) {
      localPlugins.put(DefaultPluginMetadata.create(pluginKey), pluginInstance);
      return this;
    }

    @Override
    public Map<PluginMetadata, SonarPlugin> localPlugins() {
      return localPlugins;
    }

  }

}
