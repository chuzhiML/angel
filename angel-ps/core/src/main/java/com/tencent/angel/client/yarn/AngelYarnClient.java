/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.angel.client.yarn;

import com.google.protobuf.ServiceException;
import com.tencent.angel.client.AngelClient;
import com.tencent.angel.common.Location;
import com.tencent.angel.conf.AngelConfiguration;
import com.tencent.angel.exception.AngelException;
import com.tencent.angel.ipc.TConnection;
import com.tencent.angel.ipc.TConnectionManager;
import com.tencent.angel.master.yarn.util.AngelApps;
import com.tencent.angel.protobuf.generated.ClientMasterServiceProtos.PingRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.JobSubmissionFiles;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.filecache.ClientDistributedCacheManager;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Angel client used on YARN deploy mode.
 */
@SuppressWarnings("deprecation")
public class AngelYarnClient extends AngelClient {
  private static final Log LOG = LogFactory.getLog(AngelYarnClient.class);
  
  /**used for upload application resource files*/
  private FileSystem jtFs;
  
  /**the tokens to access YARN resourcemanager*/
  private final Credentials credentials;
  
  /**rpc to YARN record factory*/
  private RecordFactory recordFactory;

  /**rpc client to YARN resourcemanager*/
  private YarnClient yarnClient;
  
  /**YARN application id*/
  private ApplicationId appId;
  
  private static final String UNAVAILABLE = "N/A";
  final public static FsPermission JOB_DIR_PERMISSION = FsPermission.createImmutable((short) 0777);

  /**
   * 
   * Create a new AngelYarnClient.
   *
   * @param conf application configuration
   */
  public AngelYarnClient(Configuration conf) {
    super(conf);
    credentials = new Credentials();
  }
  
  @Override
  public void submit() throws AngelException {
    try {
      setUser();
      setLocalAddr();
      Path stagingDir = AngelApps.getStagingDir(conf, userName);

      // 2.get job id
      yarnClient = YarnClient.createYarnClient();
      YarnConfiguration yarnConf = new YarnConfiguration(conf);
      yarnClient.init(yarnConf);
      yarnClient.start();
      YarnClientApplication newApp;

      newApp = yarnClient.createApplication();
      GetNewApplicationResponse newAppResponse = newApp.getNewApplicationResponse();
      appId = newAppResponse.getApplicationId();
      JobID jobId = TypeConverter.fromYarn(appId);

      Path submitJobDir = new Path(stagingDir, appId.toString());
      jtFs = submitJobDir.getFileSystem(conf);

      conf.set("hadoop.http.filter.initializers",
          "org.apache.hadoop.yarn.server.webproxy.amfilter.AmFilterInitializer");
      conf.set(AngelConfiguration.ANGEL_JOB_DIR, submitJobDir.toString());
      conf.set(AngelConfiguration.ANGEL_JOB_ID, jobId.toString());

      setOutputDirectory();

      // Credentials credentials = new Credentials();
      TokenCache.obtainTokensForNamenodes(credentials, new Path[] {submitJobDir}, conf);
      checkParameters(conf);

      // 4.copy resource files to hdfs
      copyAndConfigureFiles(conf, submitJobDir, (short) 10);

      // 5.write configuration to a xml file
      Path submitJobFile = JobSubmissionFiles.getJobConfPath(submitJobDir);
      TokenCache.cleanUpTokenReferral(conf);
      writeConf(conf, submitJobFile);

      // 6.create am container context
      ApplicationSubmissionContext appContext =
          createApplicationSubmissionContext(conf, submitJobDir, credentials, appId);

      conf.set(AngelConfiguration.ANGEL_JOB_LIBJARS, "");

      // 7.Submit to ResourceManager
      appId = yarnClient.submitApplication(appContext);

      // 8.get app master client
      updateMaster(Integer.MAX_VALUE);
    } catch (Exception x) {
      LOG.error("submit application to yarn failed.", x);
      throw new AngelException(x);
    }
  }

  @Override
  public void stop() throws AngelException{
    if (yarnClient != null) {
      try {
        yarnClient.killApplication(appId);
      } catch (YarnException | IOException e) {
        throw new AngelException(e);
      }
      yarnClient.stop();
    }
    close();
  }

  private void copyAndConfigureFiles(Configuration conf, Path submitJobDir, short i)
      throws IOException {

    String files = conf.get(AngelConfiguration.ANGEL_JOB_CACHE_FILES);
    String libjars = conf.get(AngelConfiguration.ANGEL_JOB_LIBJARS);
    String archives = conf.get(AngelConfiguration.ANGEL_JOB_CACHE_ARCHIVES);

    // Create a number of filenames in the JobTracker's fs namespace
    LOG.info("default FileSystem: " + jtFs.getUri());
    if (jtFs.exists(submitJobDir)) {
      throw new IOException("Not submitting job. Job directory " + submitJobDir
          + " already exists!! This is unexpected.Please check what's there in" + " that directory");
    }
    submitJobDir = jtFs.makeQualified(submitJobDir);
    submitJobDir = new Path(submitJobDir.toUri().getPath());
    FsPermission angelSysPerms = new FsPermission(JOB_DIR_PERMISSION);
    FileSystem.mkdirs(jtFs, submitJobDir, angelSysPerms);
    Path filesDir = JobSubmissionFiles.getJobDistCacheFiles(submitJobDir);
    Path archivesDir = JobSubmissionFiles.getJobDistCacheArchives(submitJobDir);
    Path libjarsDir = JobSubmissionFiles.getJobDistCacheLibjars(submitJobDir);

    LOG.info("libjarsDir=" + libjarsDir);
    LOG.info("libjars=" + libjars);
    // add all the command line files/ jars and archive
    // first copy them to jobtrackers filesystem

    if (files != null) {
      FileSystem.mkdirs(jtFs, filesDir, angelSysPerms);
      String[] fileArr = files.split(",");
      for (String tmpFile : fileArr) {
        URI tmpURI = null;
        try {
          tmpURI = new URI(tmpFile);
        } catch (URISyntaxException e) {
          throw new IllegalArgumentException(e);
        }
        Path tmp = new Path(tmpURI);
        Path newPath = copyRemoteFiles(filesDir, tmp, conf, i);
        try {
          URI pathURI = getPathURI(newPath, tmpURI.getFragment());
          DistributedCache.addCacheFile(pathURI, conf);
        } catch (URISyntaxException ue) {
          // should not throw a uri exception
          throw new IOException("Failed to create uri for " + tmpFile, ue);
        }
      }
    }

    if (libjars != null) {
      FileSystem.mkdirs(jtFs, libjarsDir, angelSysPerms);
      String[] libjarsArr = libjars.split(",");
      for (String tmpjars : libjarsArr) {
        Path tmp = new Path(tmpjars);
        Path newPath = copyRemoteFiles(libjarsDir, tmp, conf, i);
        DistributedCache.addFileToClassPath(new Path(newPath.toUri().getPath()), conf);
      }
    }

    if (archives != null) {
      FileSystem.mkdirs(jtFs, archivesDir, angelSysPerms);
      String[] archivesArr = archives.split(",");
      for (String tmpArchives : archivesArr) {
        URI tmpURI;
        try {
          tmpURI = new URI(tmpArchives);
        } catch (URISyntaxException e) {
          throw new IllegalArgumentException(e);
        }
        Path tmp = new Path(tmpURI);
        Path newPath = copyRemoteFiles(archivesDir, tmp, conf, i);
        try {
          URI pathURI = getPathURI(newPath, tmpURI.getFragment());
          DistributedCache.addCacheArchive(pathURI, conf);
        } catch (URISyntaxException ue) {
          // should not throw an uri excpetion
          throw new IOException("Failed to create uri for " + tmpArchives, ue);
        }
      }
    }

    // set the timestamps of the archives and files
    // set the public/private visibility of the archives and files
    ClientDistributedCacheManager.determineTimestampsAndCacheVisibilities(conf);
    // get DelegationToken for each cached file
    ClientDistributedCacheManager.getDelegationTokens(conf, credentials);
  }

  private Path copyRemoteFiles(Path parentDir, Path originalPath, Configuration conf,
      short replication) throws IOException {
    // check if we do not need to copy the files
    // is jt using the same file system.
    // just checking for uri strings... doing no dns lookups
    // to see if the filesystems are the same. This is not optimal.
    // but avoids name resolution.

    FileSystem remoteFs = null;
    remoteFs = originalPath.getFileSystem(conf);
    if (compareFs(remoteFs, jtFs)) {
      return originalPath;
    }
    // this might have name collisions. copy will throw an exception
    // parse the original path to create new path
    Path newPath = new Path(parentDir, originalPath.getName());
    FileUtil.copy(remoteFs, originalPath, jtFs, newPath, false, conf);
    jtFs.setReplication(newPath, replication);
    return newPath;
  }

  private boolean compareFs(FileSystem srcFs, FileSystem destFs) {
    URI srcUri = srcFs.getUri();
    URI dstUri = destFs.getUri();
    if (srcUri.getScheme() == null) {
      return false;
    }
    if (!srcUri.getScheme().equals(dstUri.getScheme())) {
      return false;
    }
    String srcHost = srcUri.getHost();
    String dstHost = dstUri.getHost();
    if ((srcHost != null) && (dstHost != null)) {
      try {
        srcHost = InetAddress.getByName(srcHost).getCanonicalHostName();
        dstHost = InetAddress.getByName(dstHost).getCanonicalHostName();
      } catch (UnknownHostException ue) {
        return false;
      }
      if (!srcHost.equals(dstHost)) {
        return false;
      }
    } else if (srcHost == null && dstHost != null) {
      return false;
    } else if (srcHost != null && dstHost == null) {
      return false;
    }
    // check for ports
    if (srcUri.getPort() != dstUri.getPort()) {
      return false;
    }
    return true;
  }

  private URI getPathURI(Path destPath, String fragment) throws URISyntaxException {
    URI pathURI = destPath.toUri();
    if (pathURI.getFragment() == null) {
      if (fragment == null) {
        pathURI = new URI(pathURI.toString() + "#" + destPath.getName());
      } else {
        pathURI = new URI(pathURI.toString() + "#" + fragment);
      }
    }
    return pathURI;
  }

  private void writeConf(Configuration conf, Path jobFile) throws IOException {
    // Write job file to JobTracker's fs
    FSDataOutputStream out =
        FileSystem.create(jtFs, jobFile, new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION));
    try {
      conf.writeXml(out);
    } finally {
      out.close();
    }
  }

  public ApplicationSubmissionContext createApplicationSubmissionContext(Configuration jobConf,
      Path jobSubmitPath, Credentials ts, ApplicationId appId) throws IOException {
    ApplicationId applicationId = appId;

    // Setup resource requirements
    recordFactory = RecordFactoryProvider.getRecordFactory(null);
    Resource capability = recordFactory.newRecordInstance(Resource.class);
    capability.setMemory(conf.getInt(AngelConfiguration.ANGEL_AM_VMEM_MB,
        AngelConfiguration.DEFAULT_ANGEL_AM_VMEM_MB));
    capability.setVirtualCores(conf.getInt(AngelConfiguration.ANGEL_AM_CPU_VCORES,
        AngelConfiguration.DEFAULT_ANGEL_AM_CPU_VCORES));
    System.out.println("AppMaster capability = " + capability);

    // Setup LocalResources
    Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();

    Path jobConfPath = new Path(jobSubmitPath, AngelConfiguration.ANGEL_JOB_CONF_FILE);

    FileContext defaultFileContext = FileContext.getFileContext(this.conf);

    System.out.println("before localResources.put(AngelConfiguration.ANGEL_JOB_CONF_FILE)");

    localResources.put(AngelConfiguration.ANGEL_JOB_CONF_FILE,
        createApplicationResource(defaultFileContext, jobConfPath, LocalResourceType.FILE));

    // Setup security tokens
    DataOutputBuffer dob = new DataOutputBuffer();
    ts.writeTokenStorageToStream(dob);
    ByteBuffer securityTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());

    // Setup the command to run the AM
    List<String> vargs = new ArrayList<String>(8);
    vargs.add(Environment.JAVA_HOME.$() + "/bin/java");

    long logSize = 0;
    String logLevel =
        jobConf.get(AngelConfiguration.ANGEL_AM_LOG_LEVEL,
            AngelConfiguration.DEFAULT_ANGEL_AM_LOG_LEVEL);
    AngelApps.addLog4jSystemProperties(logLevel, logSize, vargs);

    // Add AM user command opts
    String angelAppMasterUserOptions =
        conf.get(AngelConfiguration.ANGEL_AM_JAVA_OPTS,
            AngelConfiguration.DEFAULT_ANGEL_AM_JAVA_OPTS);
    vargs.add(angelAppMasterUserOptions);
    vargs.add(conf.get(AngelConfiguration.ANGEL_AM_CLASS, AngelConfiguration.DEFAULT_ANGEL_AM_CLASS));
    vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + Path.SEPARATOR
        + ApplicationConstants.STDOUT);
    vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + Path.SEPARATOR
        + ApplicationConstants.STDERR);

    Vector<String> vargsFinal = new Vector<String>(8);
    // Final command
    StringBuilder mergedCommand = new StringBuilder();
    for (CharSequence str : vargs) {
      mergedCommand.append(str).append(" ");
    }
    vargsFinal.add(mergedCommand.toString());

    LOG.info("Command to launch container for ApplicationMaster is : " + mergedCommand);

    // Setup the CLASSPATH in environment
    // i.e. add { Hadoop jars, job jar, CWD } to classpath.
    Map<String, String> environment = new HashMap<String, String>();
    AngelApps.setClasspath(environment, conf);

    // Setup the environment variables for Admin first
    // Setup the environment variables (LD_LIBRARY_PATH, etc)
    AngelApps.setEnvFromInputString(environment, conf.get(
        AngelConfiguration.ANGEL_AM_ADMIN_USER_ENV,
        AngelConfiguration.DEFAULT_ANGEL_AM_ADMIN_USER_ENV));

    AngelApps.setEnvFromInputString(environment,
        conf.get(AngelConfiguration.ANGEL_AM_ENV, AngelConfiguration.DEFAULT_ANGEL_AM_ENV));

    // Parse distributed cache
    AngelApps.setupDistributedCache(jobConf, localResources);

    Map<ApplicationAccessType, String> acls = new HashMap<ApplicationAccessType, String>(2);
    acls.put(ApplicationAccessType.VIEW_APP, jobConf.get(AngelConfiguration.JOB_ACL_VIEW_JOB,
        AngelConfiguration.DEFAULT_JOB_ACL_VIEW_JOB));
    acls.put(ApplicationAccessType.MODIFY_APP, jobConf.get(AngelConfiguration.JOB_ACL_MODIFY_JOB,
        AngelConfiguration.DEFAULT_JOB_ACL_MODIFY_JOB));

    // Setup ContainerLaunchContext for AM container
    ContainerLaunchContext amContainer =
        ContainerLaunchContext.newInstance(localResources, environment, vargsFinal, null,
            securityTokens, acls);

    // Set up the ApplicationSubmissionContext
    ApplicationSubmissionContext appContext =
        recordFactory.newRecordInstance(ApplicationSubmissionContext.class);
    appContext.setApplicationId(applicationId); // ApplicationId

    String queue = conf.get(AngelConfiguration.ANGEL_QUEUE, YarnConfiguration.DEFAULT_QUEUE_NAME);
    appContext.setQueue(queue); // Queue name
    System.out.println("XXX ApplicationSubmissionContext Queuename :  " + queue);
    appContext.setApplicationName(conf.get(AngelConfiguration.ANGEL_JOB_NAME,
        AngelConfiguration.DEFAULT_ANGEL_JOB_NAME));
    appContext.setCancelTokensWhenComplete(conf.getBoolean(
        AngelConfiguration.JOB_CANCEL_DELEGATION_TOKEN, true));
    appContext.setAMContainerSpec(amContainer); // AM Container
    appContext.setMaxAppAttempts(conf.getInt(AngelConfiguration.ANGEL_AM_MAX_ATTEMPTS,
        AngelConfiguration.DEFAULT_ANGEL_AM_MAX_ATTEMPTS));
    appContext.setResource(capability);
    appContext.setApplicationType(AngelConfiguration.ANGEL_APPLICATION_TYPE);
    return appContext;
  }

  private LocalResource createApplicationResource(FileContext fs, Path p, LocalResourceType type)
      throws IOException {
    LocalResource rsrc = recordFactory.newRecordInstance(LocalResource.class);
    FileStatus rsrcStat = fs.getFileStatus(p);
    rsrc.setResource(ConverterUtils.getYarnUrlFromPath(fs.getDefaultFileSystem().resolvePath(
        rsrcStat.getPath())));
    rsrc.setSize(rsrcStat.getLen());
    rsrc.setTimestamp(rsrcStat.getModificationTime());
    rsrc.setType(type);
    rsrc.setVisibility(LocalResourceVisibility.APPLICATION);
    return rsrc;
  }

  @Override
  protected void updateMaster(int maxWaitSeconds) throws Exception  {
    String host = null;
    int port = -1;
    int tryTime = 0;
    TConnection connection = TConnectionManager.getConnection(conf);
    while (tryTime < maxWaitSeconds) {
      ApplicationReport appMaster = yarnClient.getApplicationReport(appId);
      String diagnostics =
          (appMaster == null ? "application report is null" : appMaster.getDiagnostics());
      if (appMaster == null || appMaster.getYarnApplicationState() == YarnApplicationState.FAILED
          || appMaster.getYarnApplicationState() == YarnApplicationState.KILLED) {
        throw new IOException("Failed to run job : " + diagnostics);
      }
      host = appMaster.getHost();
      port = appMaster.getRpcPort();
      if (host == null || "".equals(host)) {
        LOG.info("AM not assigned to Job. Waiting to get the AM ...");
        Thread.sleep(1000);
        tryTime++;
        continue;
      } else if (UNAVAILABLE.equals(host)) {
        Thread.sleep(1000);
        tryTime++;
        continue;
      } else {
        String httpHistory =
            "appMaster getTrackingUrl = "
                + appMaster.getTrackingUrl().replace("proxy", "cluster/app");
        LOG.info(httpHistory);
        LOG.info("master host=" + host + ", port=" + port);
        try {
          masterLocation = new Location(host, port);
          LOG.info("start to create rpc client to am");         
          master = connection.getMasterService(masterLocation.getIp(), masterLocation.getPort());
          master.ping(null, PingRequest.newBuilder().build());
        } catch (ServiceException e) {
          Thread.sleep(1000);
          tryTime++;
          continue;
        }
        break;
      }
    }
  }

  @Override
  protected String getAppId() {
    return appId.toString();
  }
}