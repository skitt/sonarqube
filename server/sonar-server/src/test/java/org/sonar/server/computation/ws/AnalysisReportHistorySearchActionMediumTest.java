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

package org.sonar.server.computation.ws;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.security.DefaultGroups;
import org.sonar.api.web.UserRole;
import org.sonar.core.activity.Activity;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.computation.db.AnalysisReportDto;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.permission.PermissionFacade;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.user.UserDto;
import org.sonar.server.activity.ActivityService;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.computation.AnalysisReportLog;
import org.sonar.server.computation.AnalysisReportQueue;
import org.sonar.server.computation.ComputationService;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.tester.ServerTester;
import org.sonar.server.user.MockUserSession;
import org.sonar.server.ws.WsTester;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class AnalysisReportHistorySearchActionMediumTest {
  private static final String DEFAULT_PROJECT_KEY = "DefaultProjectKey";
  private static final String DEFAULT_PROJECT_NAME = "DefaultProjectName";

  @ClassRule
  public static ServerTester tester = new ServerTester();

  private DbClient dbClient;
  private DbSession session;
  private WsTester wsTester;
  private AnalysisReportQueue queue;
  private MockUserSession userSession;
  private ComputationService computationService;
  private ActivityService activityService;

  @Before
  public void before() {
    tester.clearDbAndIndexes();
    dbClient = tester.get(DbClient.class);
    wsTester = tester.get(WsTester.class);
    session = dbClient.openSession(false);
    queue = tester.get(AnalysisReportQueue.class);
    activityService = tester.get(ActivityService.class);

    UserDto connectedUser = new UserDto().setLogin("gandalf").setName("Gandalf");
    dbClient.userDao().insert(session, connectedUser);

    userSession = MockUserSession.set()
      .setLogin(connectedUser.getLogin())
      .setUserId(connectedUser.getId().intValue())
      .setGlobalPermissions(GlobalPermissions.SCAN_EXECUTION);
  }

  @After
  public void after() {
    MyBatis.closeQuietly(session);
  }

  @Test
  public void add_and_try_to_retrieve_activities() throws Exception {
    insertPermissionsForProject(DEFAULT_PROJECT_KEY);
    queue.add(DEFAULT_PROJECT_KEY, 123L);
    queue.add(DEFAULT_PROJECT_KEY, 123L);
    queue.add(DEFAULT_PROJECT_KEY, 123L);

    List<AnalysisReportDto> reports = queue.all();
    ComponentDto project = ComponentTesting.newProjectDto()
      .setName(DEFAULT_PROJECT_NAME)
      .setKey(DEFAULT_PROJECT_KEY);
    for (AnalysisReportDto report : reports) {
      report.succeed();
      activityService.write(session, Activity.Type.ANALYSIS_REPORT, new AnalysisReportLog(report, project));
    }

    session.commit();
    userSession.setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN);

    WsTester.TestRequest request = wsTester.newGetRequest(AnalysisReportWebService.API_ENDPOINT, AnalysisReportHistorySearchAction.SEARCH_ACTION);
    WsTester.Result result = request.execute();

    assertThat(result).isNotNull();
    result.assertJson(getClass(), "list_history_reports.json", false);
  }

  private ComponentDto insertPermissionsForProject(String projectKey) {
    ComponentDto project = new ComponentDto().setKey(projectKey).setId(1L);
    dbClient.componentDao().insert(session, project);

    tester.get(PermissionFacade.class).insertGroupPermission(project.getId(), DefaultGroups.ANYONE, UserRole.USER, session);
    userSession.addProjectPermissions(UserRole.ADMIN, project.key());
    userSession.addProjectPermissions(UserRole.USER, project.key());

    session.commit();

    return project;
  }

  @Test(expected = ForbiddenException.class)
  public void user_rights_is_not_enough_throw_ForbiddenException() throws Exception {
    insertPermissionsForProject(DEFAULT_PROJECT_KEY);
    queue.add(DEFAULT_PROJECT_KEY, 123L);

    AnalysisReportDto report = queue.all().get(0);
    report.succeed();
    queue.remove(report);
    userSession.setGlobalPermissions(GlobalPermissions.SCAN_EXECUTION);

    WsTester.TestRequest sut = wsTester.newGetRequest(AnalysisReportWebService.API_ENDPOINT, AnalysisReportHistorySearchAction.SEARCH_ACTION);
    sut.execute();
  }
}
