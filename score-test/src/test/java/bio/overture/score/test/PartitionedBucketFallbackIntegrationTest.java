/*
 * Copyright (c) 2016 The Ontario Institute for Cancer Research. All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package bio.overture.score.test;

import static bio.overture.score.test.util.Assertions.assertDirectories;
import static bio.overture.score.test.util.SpringBootProcess.bootRun;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.repeat;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;

import bio.overture.score.test.auth.AuthClient;
import bio.overture.score.test.fs.FileSystem;
import bio.overture.score.test.meta.Entity;
import bio.overture.score.test.meta.MetadataClient;
import bio.overture.score.test.mongo.Mongo;
import bio.overture.score.test.s3.S3;
import bio.overture.score.test.util.Port;
import java.io.File;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@Slf4j
public class PartitionedBucketFallbackIntegrationTest {

  /** Configuration. */
  protected final int authPort = 8443;

  protected final int metadataPort = 8444;
  protected final int storagePort = 5431;

  final String gnosId = "70b07570-0571-11e5-a6c0-1697f925ec7b";

  final String objectBucketBase = "oicr.icgc.dev";
  final String stateBucketBase = "oicr.icgc.dev.state";
  final int numBucketPartitions = 5;
  final int bucketKeyLength = 2;

  /** State. */
  final Mongo mongo = new Mongo();

  final S3 s3 = new S3();
  protected final FileSystem fs = new FileSystem(new File("target/test"), gnosId);

  Process authServer;
  Process metaServer;
  Process storageServer;

  @Rule public TemporaryFolder s3Root = new TemporaryFolder();

  public PartitionedBucketFallbackIntegrationTest() {
    super();
  }

  @Before
  public void setUp() throws Exception {
    log.info("starting up");
    banner("Starting file system...");
    fs.start();

    banner("Starting Mongo...");
    mongo.start();

    //
    // NB: this test ignores the baseDir property set in application.conf in favour
    // of the temporary path generated by junit. This isolates the S3 instances should
    // they ever need to run in parallel in the same environment.
    String s3ninjaPath = s3Root.getRoot().getAbsolutePath();
    banner("setting up S3 buckets in: " + s3ninjaPath + "...");
    setupBuckets(s3Root.getRoot(), numBucketPartitions);

    banner("Starting S3 under " + s3ninjaPath + " ...");
    s3.start(s3Root.getRoot());

    //    banner("Starting dcc-auth-server...");
    //    authServer = authServer();

    banner("Starting dcc-metadata-server...");
    metaServer = metadataServer();

    banner("Starting score-server...");
    storageServer = storageServer();

    banner("Waiting for service ports...");
    waitForPort(authPort);
    waitForPort(metadataPort);
    waitForPort(storagePort);
  }

  @After
  public void tearDown() {
    log.info("Shutting down");

    //    log.info("Stopping Auth server");
    //    if (authServer != null) authServer.destroy();

    log.info("Stopping Meta server");
    if (metaServer != null) metaServer.destroy();

    log.info("Stopping Storage server");
    if (storageServer != null) storageServer.destroy();

    log.info("Stopping Mongo");
    mongo.stop();

    log.info("Stopping S3Ninja");
    s3.stop();
  }

  Process storageServer() {
    int debugPort =
        Integer.parseInt(firstNonNull(System.getProperty("storage.server.debugPort"), "-1"));

    return bootRun(
        resolveJarFile("score-server"),
        debugPort,
        "-Dspring.profiles.active=dev,secure,default", // Secure
        "-Dlogging.file=" + fs.getLogsDir() + "/score-server.log",
        "-Dserver.port=" + storagePort,
        "-Dbucket.name.object=" + objectBucketBase,
        "-Dbucket.size.pool=" + numBucketPartitions,
        "-Dbucket.size.key=" + bucketKeyLength,
        "-Dbucket.name.state=" + stateBucketBase,
        "-Dauth.server.url=https://localhost:" + authPort + "/oauth/check_token",
        "-Dauth.server.clientId=storage",
        "-Dauth.server.clientsecret=pass",
        "-Dmetadata.url=https://localhost:" + metadataPort,
        "-Dendpoints.jmx.domain=storage");
  }

  Process storageClient(String accessToken, String... args) {
    int debugPort =
        Integer.parseInt(firstNonNull(System.getProperty("storage.client.debugPort"), "-1"));

    return bootRun(
        resolveJarFile("score-client"),
        debugPort,
        args,
        "-Dlogging.file=" + fs.getLogsDir() + "/score-client.log",
        "-Dmetadata.url=https://localhost:" + metadataPort,
        "-Dmetadata.ssl.enabled=false",
        "-Dstorage.url=http://localhost:" + storagePort,
        "-DaccessToken=" + accessToken);
  }

  public void setupBuckets(File s3Root) {
    setupBuckets(s3Root, 0);
  }

  public void setupBuckets(File s3Root, int count) {

    String objectBucket = "";
    String stateBucket = "";
    if (count > 0) {
      for (int i = 0; i < count; i++) {
        objectBucket = String.format("%s.%d", objectBucketBase, i);
        stateBucket = String.format("%s.%d", stateBucketBase, i);
        createBucket(s3Root, objectBucket);
        createBucket(s3Root, stateBucket);
      }
    } else {
      createBucket(s3Root, objectBucketBase);
      createBucket(s3Root, stateBucketBase);
    }
  }

  void createBucket(File s3Root, String name) {
    // create expected S3 buckets
    File bucket = new File(s3Root, name);
    bucket.mkdir();

    if (!bucket.exists()) {
      throw new RuntimeException(
          "Unable to create working directory " + s3Root.getAbsolutePath() + name);
    }
  }

  @Ignore("Test ignored until Metadata Client producing manifest issue is resolved")
  @Test
  public void test() throws InterruptedException {

    //
    // Authorize
    //

    banner("Authorizing...");
    val accessToken = new AuthClient("https://localhost:" + authPort).createAccessToken();

    //
    // Register
    //

    banner("Registering...");
    val register =
        metadataClient(
            accessToken,
            "-i",
            fs.getUploadsDir() + "/" + gnosId,
            "-m",
            "manifest.txt",
            "-o",
            fs.getRootDir().toString());
    register.waitFor(1, MINUTES);

    assertThat(register.exitValue()).isEqualTo(0);

    //
    // Upload
    //

    banner("Uploading...");
    for (int status = 0; status < 2; status++) {
      val upload =
          storageClient(
              accessToken, "upload", "--manifest", "src/test/resources/upload-manifest.txt");
      // "--manifest", fs.getRootDir() + "/manifest.txt");
      upload.waitFor(10, MINUTES);
      assertThat(upload.exitValue())
          .isEqualTo(status); // First time 0, second time 1 since no --force
    }

    //
    // Find
    //

    val entities = findEntities(gnosId);
    assertThat(entities).isNotEmpty();

    //
    // URL
    //

    banner("URLing " + entities.get(0));
    val url = storageClient(accessToken, "url", "--object-id", entities.get(0).getId());
    url.waitFor(1, MINUTES);
    assertThat(url.exitValue()).isEqualTo(0);

    //
    // Download
    //

    banner("Downloading...");
    for (val entity : entities) {
      if (isBaiFile(entity)) {
        // Skip BAI files since these will be downloaded when the BAM file is requested
        continue;
      }

      val download =
          storageClient(
              accessToken,
              "download",
              "--object-id",
              entity.getId(),
              "--output-layout",
              "bundle",
              "--output-dir",
              fs.getDownloadsDir().toString());
      download.waitFor(1, MINUTES);

      assertThat(download.exitValue()).isEqualTo(0);
    }

    assertDirectories(fs.getDownloadsDir(), fs.getUploadsDir());

    //
    // View
    //

    val bamFile = getBamFile(entities);
    banner("Viewing " + bamFile);
    val view =
        storageClient(
            accessToken,
            "view",
            "--header-only",
            "--input-file",
            new File(new File(fs.getDownloadsDir(), bamFile.getGnosId()), bamFile.getFileName())
                .toString(),
            "--output-format",
            "sam");
    view.waitFor(1, MINUTES);
    assertThat(view.exitValue()).isEqualTo(0);
  }

  //  Process authServer() {
  //    int debugPort =
  //        Integer.parseInt(firstNonNull(System.getProperty("auth.server.debugPort"), "-1"));
  //
  //    return bootRun(
  //        org.icgc.dcc.auth.server.ServerMain.class,
  //        debugPort,
  //        "-Dspring.profiles.active=dev,no_scope_validation", // Don't validate if user has scopes
  //        "-Dlogging.file=" + fs.getLogsDir() + "/dcc-auth-server.log",
  //        "-Dserver.port=" + authPort,
  //        "-Dmanagement.port=8543",
  //        "-Dendpoints.jmx.domain=auth");
  //  }

  Process metadataServer() {
    int debugPort =
        Integer.parseInt(firstNonNull(System.getProperty("meta.server.debugPort"), "-1"));

    return bootRun(
        "dcc-metadata-server",
        debugPort,
        "-Dspring.profiles.active=development,secure", // Secure
        "-Dlogging.file=" + fs.getLogsDir() + "/dcc-metadata-server.log",
        "-Dserver.port=" + metadataPort,
        "-Dmanagement.port=8544",
        "-Dendpoints.jmx.domain=metadata",
        "-Dauth.server.url=https://localhost:" + authPort + "/oauth/check_token",
        "-Dauth.server.clientId=metadata",
        "-Dauth.server.clientsecret=pass",
        "-Dspring.data.mongodb.uri=mongodb://localhost:" + mongo.getPort() + "/dcc-metadata");
  }

  private Process metadataClient(String accessToken, String... args) {
    int debugPort =
        Integer.parseInt(firstNonNull(System.getProperty("meta.client.debugPort"), "-1"));

    return bootRun(
        "dcc-metadata-client",
        debugPort,
        args,
        "-Dspring.profiles.active=development",
        "-Dlogging.file=" + fs.getLogsDir() + "/dcc-metadata-client.log",
        "-Dserver.baseUrl=https://localhost:" + metadataPort,
        "-DaccessToken=" + accessToken);
  }

  List<Entity> findEntities(String gnosId) {
    val metadataClient = new MetadataClient("https://localhost:" + metadataPort, false);
    return metadataClient.findEntitiesByGnosId(gnosId);
  }

  private static Entity getBamFile(List<Entity> entities) {
    return entities.stream()
        .filter(entity -> entity.getFileName().endsWith(".bam"))
        .findFirst()
        .get();
  }

  static boolean isBaiFile(Entity entity) {
    return entity.getFileName().endsWith(".bai");
  }

  static File resolveJarFile(String artifactId) {
    val targetDir = new File("../" + artifactId + "/target");
    return targetDir
        .listFiles(
            (File file, String name) -> name.startsWith(artifactId) && name.endsWith(".jar"))[0];
  }

  static void waitForPort(int port) {
    new Port("localhost", port).waitFor(1, MINUTES);
  }

  static void banner(String text) {
    log.info("");
    log.info(repeat("#", 100));
    log.info(text);
    log.info(repeat("#", 100));
    log.info("");
  }
}
