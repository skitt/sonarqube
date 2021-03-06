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

package org.sonar.server.component;

import org.sonar.api.ServerComponent;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.preview.PreviewCache;
import org.sonar.core.resource.ResourceKeyUpdaterDao;
import org.sonar.server.db.DbClient;
import org.sonar.server.user.UserSession;

import javax.annotation.CheckForNull;

import java.util.Map;

public class ComponentService implements ServerComponent {

  private final DbClient dbClient;

  private final ResourceKeyUpdaterDao resourceKeyUpdaterDao;
  private final PreviewCache previewCache;

  public ComponentService(DbClient dbClient, ResourceKeyUpdaterDao resourceKeyUpdaterDao, PreviewCache previewCache) {
    this.dbClient = dbClient;
    this.resourceKeyUpdaterDao = resourceKeyUpdaterDao;
    this.previewCache = previewCache;
  }

  public ComponentDto getByKey(String key) {
    DbSession session = dbClient.openSession(false);
    try {
      return dbClient.componentDao().getByKey(session, key);
    } finally {
      session.close();
    }
  }

  @CheckForNull
  public ComponentDto getNullableByKey(String key) {
    DbSession session = dbClient.openSession(false);
    try {
      return dbClient.componentDao().getNullableByKey(session, key);
    } finally {
      session.close();
    }
  }

  public ComponentDto getByUuid(String uuid) {
    DbSession session = dbClient.openSession(false);
    try {
      return dbClient.componentDao().getByUuid(session, uuid);
    } finally {
      session.close();
    }
  }

  @CheckForNull
  public ComponentDto getNullableByUuid(String uuid) {
    DbSession session = dbClient.openSession(false);
    try {
      return dbClient.componentDao().getNullableByUuid(session, uuid);
    } finally {
      session.close();
    }
  }

  public void updateKey(String projectOrModuleKey, String newKey) {
    UserSession.get().checkComponentPermission(UserRole.ADMIN, projectOrModuleKey);

    DbSession session = dbClient.openSession(false);
    try {
      ComponentDto projectOrModule = getByKey(projectOrModuleKey);
      resourceKeyUpdaterDao.updateKey(projectOrModule.getId(), newKey);
      session.commit();

      previewCache.reportResourceModification(newKey);

      session.commit();
    } finally {
      session.close();
    }
  }

  public Map<String, String> checkModuleKeysBeforeRenaming(String projectKey, String stringToReplace, String replacementString) {
    UserSession.get().checkProjectPermission(UserRole.ADMIN, projectKey);
    DbSession session = dbClient.openSession(false);
    try {
      ComponentDto project = getByKey(projectKey);
      return resourceKeyUpdaterDao.checkModuleKeysBeforeRenaming(project.getId(), stringToReplace, replacementString);
    } finally {
      session.close();
    }
  }

  public void bulkUpdateKey(String projectKey, String stringToReplace, String replacementString) {
    UserSession.get().checkProjectPermission(UserRole.ADMIN, projectKey);

    DbSession session = dbClient.openSession(false);
    try {
      ComponentDto project = getByKey(projectKey);

      resourceKeyUpdaterDao.bulkUpdateKey(project.getId(), stringToReplace, replacementString);
      session.commit();

      ComponentDto newProject = dbClient.componentDao().getById(project.getId(), session);
      previewCache.reportResourceModification(newProject.key());

      session.commit();
    } finally {
      session.close();
    }
  }

}
