/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.jdbc.minikdc;


import com.google.common.io.Files;
import org.apache.hadoop.minikdc.MiniKdc;
import org.apache.hadoop.security.GroupMappingServiceProvider;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.jdbc.miniSS2.MiniSS2;
import org.apache.spark.sql.service.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Wrapper around Hadoop's MiniKdc for use in hive tests.
 * Has functions to manager users and their keytabs. This includes a hive service principal,
 * a superuser principal for testing proxy user privilegs.
 * Has a set of default users that it initializes.
 * See hive-minikdc/src/test/resources/core-site.xml for users granted proxy user privileges.
 */
public class MiniHiveKdc {
  public static String HIVE_SERVICE_PRINCIPAL = "hive";
  public static String HIVE_TEST_USER_1 = "user1";
  public static String HIVE_TEST_USER_2 = "user2";
  public static String HIVE_TEST_SUPER_USER = "superuser";
  public static String AUTHENTICATION_TYPE = "KERBEROS";

  private final MiniKdc miniKdc;
  private final File workDir;
  private final Map<String, String> userPrincipals =
      new HashMap<String, String>();
  private final Properties kdcConf = MiniKdc.createConf();
  private int keyTabCounter = 1;

  // hadoop group mapping that maps user to same group
  public static class HiveTestSimpleGroupMapping implements GroupMappingServiceProvider {
    @Override
    public List<String> getGroups(String user) throws IOException {
      List<String> results = new ArrayList<String>();
      results.add(user);
      return results;
    }

    @Override
    public void cacheGroupsRefresh() throws IOException {
    }

    @Override
    public void cacheGroupsAdd(List<String> groups) throws IOException {
    }
  }

  public static MiniHiveKdc getMiniHiveKdc() throws Exception {
    return new MiniHiveKdc();
  }

  public MiniHiveKdc()
      throws Exception {
    File baseDir = Files.createTempDir();
    baseDir.deleteOnExit();
    workDir = new File(baseDir, "HiveMiniKdc");

    /**
     *  Hadoop security classes read the default realm via static initialization,
     *  before miniKdc is initialized. Hence we set the realm via a test configuration
     *  and propagate that to miniKdc.
     */
    System.clearProperty("java.security.krb5.conf");

    miniKdc = new MiniKdc(kdcConf, new File(workDir, "miniKdc"));
    miniKdc.start();

    // create default users
    addUserPrincipal(getServicePrincipalForUser(HIVE_SERVICE_PRINCIPAL));
    addUserPrincipal(HIVE_TEST_USER_1);
    addUserPrincipal(HIVE_TEST_USER_2);
    addUserPrincipal(HIVE_TEST_SUPER_USER);
  }

  public String getKeyTabFile(String principalName) {
    return userPrincipals.get(principalName);
  }

  public void shutDown() {
    miniKdc.stop();
  }

  public void addUserPrincipal(String principal) throws Exception {
    File keytab = new File(workDir, "miniKdc" + keyTabCounter++ + ".keytab");
    miniKdc.createPrincipal(keytab, principal);
    userPrincipals.put(principal, keytab.getPath());
  }

  /**
   * Login the given principal, using corresponding keytab file from internal map
   *
   * @param principal
   * @return
   * @throws Exception
   */
  public UserGroupInformation loginUser(String principal)
      throws Exception {
    UserGroupInformation.loginUserFromKeytab(principal,
        getKeyTabFile(principal));
    return Utils.getUGI();
  }

  public Properties getKdcConf() {
    return kdcConf;
  }

  public String getFullyQualifiedUserPrincipal(String shortUserName) {
    return shortUserName + "@" + miniKdc.getRealm();
  }

  public String getFullyQualifiedServicePrincipal(String shortUserName) {
    return getServicePrincipalForUser(shortUserName) + "@" + miniKdc.getRealm();
  }

  public String getServicePrincipalForUser(String shortUserName) {
    return shortUserName + "/" + miniKdc.getHost();
  }

  public String getHiveServicePrincipal() {
    return getServicePrincipalForUser(HIVE_SERVICE_PRINCIPAL);
  }

  public String getFullHiveServicePrincipal() {
    return getServicePrincipalForUser(HIVE_SERVICE_PRINCIPAL) + "@" + miniKdc.getRealm();
  }

  public String getDefaultUserPrincipal() {
    return HIVE_TEST_USER_1;
  }

  /**
   * Create a MiniSS2 with the hive service principal and keytab in MiniHiveKdc
   *
   * @param miniHiveKdc
   * @param sqlContext
   * @return new MiniSS2 instance
   * @throws Exception
   */
  public static MiniSS2 getMiniHS2WithKerb(MiniHiveKdc miniHiveKdc, SQLContext sqlContext) throws Exception {
    return getMiniHS2WithKerb(miniHiveKdc, sqlContext, AUTHENTICATION_TYPE);
  }

  /**
   * Create a MiniSS2 with the hive service principal and keytab in MiniHiveKdc
   *
   * @param miniHiveKdc
   * @param sqlContext
   * @param authType
   * @return new MiniSS2 instance
   * @throws Exception
   */
  public static MiniSS2 getMiniHS2WithKerb(MiniHiveKdc miniHiveKdc, SQLContext sqlContext,
                                           String authType) throws Exception {
    String hivePrincipal =
        miniHiveKdc.getFullyQualifiedServicePrincipal(MiniHiveKdc.HIVE_SERVICE_PRINCIPAL);
    String hiveKeytab = miniHiveKdc.getKeyTabFile(
        miniHiveKdc.getServicePrincipalForUser(MiniHiveKdc.HIVE_SERVICE_PRINCIPAL));

    return new MiniSS2.Builder().withSQLContext(sqlContext).withMiniKdc(hivePrincipal, hiveKeytab).
        withAuthenticationType(authType).build();
  }

  /**
   * Create a MiniSS2 with the hive service principal and keytab in MiniHiveKdc
   *
   * @param miniHiveKdc
   * @param sqlContext
   * @return new MiniSS2 instance
   * @throws Exception
   */
  public static MiniSS2 getMiniHS2WithKerbWithRemoteHMS(MiniHiveKdc miniHiveKdc, SQLContext sqlContext) throws Exception {
    return getMiniHS2WithKerbWithRemoteHMS(miniHiveKdc, sqlContext, AUTHENTICATION_TYPE);
  }

  /**
   * Create a MiniSS2 with the hive service principal and keytab in MiniHiveKdc. It uses remote HMS
   * and can support a different Sasl authType
   *
   * @param miniHiveKdc
   * @param sqlContext
   * @param authType
   * @return new MiniSS2 instance
   * @throws Exception
   */
  public static MiniSS2 getMiniHS2WithKerbWithRemoteHMS(MiniHiveKdc miniHiveKdc, SQLContext sqlContext,
                                                        String authType) throws Exception {
    String hivePrincipal =
        miniHiveKdc.getFullyQualifiedServicePrincipal(MiniHiveKdc.HIVE_SERVICE_PRINCIPAL);
    String hiveKeytab = miniHiveKdc.getKeyTabFile(
        miniHiveKdc.getServicePrincipalForUser(MiniHiveKdc.HIVE_SERVICE_PRINCIPAL));

    return new MiniSS2.Builder().withSQLContext(sqlContext)
        .withMiniKdc(hivePrincipal, hiveKeytab).withAuthenticationType(authType).build();
  }
}