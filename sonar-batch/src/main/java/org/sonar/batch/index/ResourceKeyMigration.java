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
package org.sonar.batch.index;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.BatchComponent;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DeprecatedDefaultInputFile;
import org.sonar.api.database.DatabaseSession;
import org.sonar.api.database.model.ResourceModel;
import org.sonar.api.resources.Directory;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Resource;
import org.sonar.api.resources.Scopes;
import org.sonar.api.utils.PathUtils;
import org.sonar.batch.util.DeprecatedKeyUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceKeyMigration implements BatchComponent {

  private static final String UNABLE_TO_UPDATE_COMPONENT_NO_MATCH_WAS_FOUND = "Unable to update component {}. No match was found.";
  private static final String COMPONENT_CHANGED_TO = "Component {} changed to {}";
  private final Logger logger;
  private final DatabaseSession session;

  private boolean migrationNeeded = false;

  public ResourceKeyMigration(DatabaseSession session) {
    this(session, LoggerFactory.getLogger(ResourceKeyMigration.class));
  }

  @VisibleForTesting
  ResourceKeyMigration(DatabaseSession session, Logger logger) {
    this.session = session;
    this.logger = logger;
  }

  public void checkIfMigrationNeeded(Project rootProject) {
    ResourceModel model = session.getSingleResult(ResourceModel.class, "key", rootProject.getEffectiveKey());
    if (model != null && StringUtils.isBlank(model.getDeprecatedKey())) {
      this.migrationNeeded = true;
    }
  }

  public void migrateIfNeeded(Project module, FileSystem fs) {
    if (migrationNeeded) {
      migrateIfNeeded(module, fs.inputFiles(fs.predicates().all()));
    }
  }

  void migrateIfNeeded(Project module, Iterable<InputFile> inputFiles) {
    logger.info("Update component keys");
    Map<String, InputFile> deprecatedFileKeyMapper = new HashMap<String, InputFile>();
    Map<String, InputFile> deprecatedTestKeyMapper = new HashMap<String, InputFile>();
    Map<String, String> deprecatedDirectoryKeyMapper = new HashMap<String, String>();
    for (InputFile inputFile : inputFiles) {
      String deprecatedKey = ((DeprecatedDefaultInputFile) inputFile).deprecatedKey();
      if (deprecatedKey != null) {
        if (InputFile.Type.TEST == inputFile.type() && !deprecatedTestKeyMapper.containsKey(deprecatedKey)) {
          deprecatedTestKeyMapper.put(deprecatedKey, inputFile);
        } else if (InputFile.Type.MAIN == inputFile.type() && !deprecatedFileKeyMapper.containsKey(deprecatedKey)) {
          deprecatedFileKeyMapper.put(deprecatedKey, inputFile);
        }
      }
    }

    ResourceModel moduleModel = session.getSingleResult(ResourceModel.class, "key", module.getEffectiveKey());
    int moduleId = moduleModel.getId();
    migrateFiles(module, deprecatedFileKeyMapper, deprecatedTestKeyMapper, deprecatedDirectoryKeyMapper, moduleId);
    migrateDirectories(deprecatedDirectoryKeyMapper, moduleId);
    session.commit();
  }

  private void migrateFiles(Project module, Map<String, InputFile> deprecatedFileKeyMapper, Map<String, InputFile> deprecatedTestKeyMapper,
    Map<String, String> deprecatedDirectoryKeyMapper,
    int moduleId) {
    // Find all FIL or CLA resources for this module
    StringBuilder hql = newResourceQuery()
      .append(" and scope = '").append(Scopes.FILE).append("' order by qualifier, key");
    Map<String, ResourceModel> disabledResourceByKey = loadDisabledResources(moduleId, hql);
    List<ResourceModel> resources = loadEnabledResources(moduleId, hql);
    for (ResourceModel resourceModel : resources) {
      String oldEffectiveKey = resourceModel.getKey();
      boolean isTest = Qualifiers.UNIT_TEST_FILE.equals(resourceModel.getQualifier());
      InputFile matchedFile = findInputFile(deprecatedFileKeyMapper, deprecatedTestKeyMapper, oldEffectiveKey, isTest);
      if (matchedFile != null) {
        String newEffectiveKey = ((DeprecatedDefaultInputFile) matchedFile).key();
        // Now compute migration of the parent dir
        String oldKey = StringUtils.substringAfterLast(oldEffectiveKey, ":");
        Resource sonarFile;
        String parentOldKey;
        if ("java".equals(resourceModel.getLanguageKey())) {
          parentOldKey = String.format("%s:%s", module.getEffectiveKey(), DeprecatedKeyUtils.getJavaFileParentDeprecatedKey(oldKey));
        } else {
          sonarFile = new File(oldKey);
          parentOldKey = String.format("%s:%s", module.getEffectiveKey(), sonarFile.getParent().getDeprecatedKey());
        }
        String parentNewKey = String.format("%s:%s", module.getEffectiveKey(), getParentKey(matchedFile));
        if (!deprecatedDirectoryKeyMapper.containsKey(parentOldKey)) {
          deprecatedDirectoryKeyMapper.put(parentOldKey, parentNewKey);
        } else if (!parentNewKey.equals(deprecatedDirectoryKeyMapper.get(parentOldKey))) {
          logger.warn("Directory with key " + parentOldKey + " matches both " + deprecatedDirectoryKeyMapper.get(parentOldKey) + " and "
            + parentNewKey + ". First match is arbitrary chosen.");
        }
        updateKey(resourceModel, newEffectiveKey, disabledResourceByKey);
        resourceModel.setDeprecatedKey(oldEffectiveKey);
        logger.info(COMPONENT_CHANGED_TO, oldEffectiveKey, newEffectiveKey);
      } else {
        logger.warn(UNABLE_TO_UPDATE_COMPONENT_NO_MATCH_WAS_FOUND, oldEffectiveKey);
      }
    }
  }

  private void updateKey(ResourceModel resourceModel, String newEffectiveKey, Map<String, ResourceModel> disabledResourceByKey) {
    // Look for disabled resource with conflicting key
    if (disabledResourceByKey.containsKey(newEffectiveKey)) {
      ResourceModel duplicateDisabledResource = disabledResourceByKey.get(newEffectiveKey);
      String disabledKey = newEffectiveKey + "_renamed_by_resource_key_migration";
      duplicateDisabledResource.setKey(disabledKey);
      logger.info(COMPONENT_CHANGED_TO, newEffectiveKey, disabledKey);
    }
    resourceModel.setKey(newEffectiveKey);
  }

  private StringBuilder newResourceQuery() {
    return new StringBuilder().append("from ")
      .append(ResourceModel.class.getSimpleName())
      .append(" where enabled = :enabled")
      .append(" and rootId = :rootId ");
  }

  private InputFile findInputFile(Map<String, InputFile> deprecatedFileKeyMapper, Map<String, InputFile> deprecatedTestKeyMapper, String oldEffectiveKey, boolean isTest) {
    if (isTest) {
      return deprecatedTestKeyMapper.get(oldEffectiveKey);
    } else {
      return deprecatedFileKeyMapper.get(oldEffectiveKey);
    }
  }

  private void migrateDirectories(Map<String, String> deprecatedDirectoryKeyMapper, int moduleId) {
    // Find all DIR resources for this module
    StringBuilder hql = newResourceQuery()
      .append(" and qualifier = '").append(Qualifiers.DIRECTORY).append("'");
    Map<String, ResourceModel> disabledResourceByKey = loadDisabledResources(moduleId, hql);
    List<ResourceModel> resources = loadEnabledResources(moduleId, hql);
    for (ResourceModel resourceModel : resources) {
      String oldEffectiveKey = resourceModel.getKey();
      if (deprecatedDirectoryKeyMapper.containsKey(oldEffectiveKey)) {
        String newEffectiveKey = deprecatedDirectoryKeyMapper.get(oldEffectiveKey);
        updateKey(resourceModel, newEffectiveKey, disabledResourceByKey);
        resourceModel.setDeprecatedKey(oldEffectiveKey);
        logger.info(COMPONENT_CHANGED_TO, oldEffectiveKey, newEffectiveKey);
      } else {
        logger.warn(UNABLE_TO_UPDATE_COMPONENT_NO_MATCH_WAS_FOUND, oldEffectiveKey);
      }
    }
  }

  private List<ResourceModel> loadEnabledResources(int moduleId, StringBuilder hql) {
    return session.createQuery(hql.toString())
      .setParameter("rootId", moduleId)
      .setParameter("enabled", true)
      .getResultList();
  }

  private Map<String, ResourceModel> loadDisabledResources(int moduleId, StringBuilder hql) {
    List<ResourceModel> disabledResources = session.createQuery(hql.toString())
      .setParameter("rootId", moduleId)
      .setParameter("enabled", false)
      .getResultList();
    Map<String, ResourceModel> disabledResourceByKey = new HashMap<String, ResourceModel>();
    for (ResourceModel disabledResourceModel : disabledResources) {
      disabledResourceByKey.put(disabledResourceModel.getKey(), disabledResourceModel);
    }
    return disabledResourceByKey;
  }

  private String getParentKey(InputFile matchedFile) {
    String filePath = PathUtils.sanitize(matchedFile.relativePath());
    String parentFolderPath;
    if (filePath.contains(Directory.SEPARATOR)) {
      parentFolderPath = StringUtils.substringBeforeLast(filePath, Directory.SEPARATOR);
    } else {
      parentFolderPath = Directory.SEPARATOR;
    }
    return parentFolderPath;
  }
}
