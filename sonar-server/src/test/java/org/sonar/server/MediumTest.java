package org.sonar.server;

import org.junit.AfterClass;
import org.junit.Before;
import org.sonar.server.tester.ServerTester;

/**
 * @since 4.4
 */
public class MediumTest {


  private final boolean autoStart;

  protected static final ServerTester tester = ServerTester.get();


  public MediumTest(){
    this(true);
  }


  public MediumTest(boolean autoStart) {
    this.autoStart = autoStart;
    if(autoStart && !tester.isStarted()){
      tester.start();
    }
  }

  @Before
  public void setup(){
    if(!autoStart && tester.isStarted()){
      tester.stop();
    } else if(autoStart && !tester.isStarted()) {
      // server has crashed...
      tester.start();
    } else if(!autoStart && tester.isStarted()){
      // testing server registration!!!
      tester.stop();
    }
  }

  @AfterClass
  public static void resetServer() {
    tester.reload();
  }
}
