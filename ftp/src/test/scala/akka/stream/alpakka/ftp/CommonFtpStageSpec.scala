/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.ftp

import java.net.InetAddress
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Paths}

import akka.stream.IOResult
import akka.stream.alpakka.ftp.SftpSupportImpl.{CLIENT_PRIVATE_KEY_PASSPHRASE => ClientPrivateKeyPassphrase}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.util.Random

final class FtpStageSpec extends BaseFtpSpec with CommonFtpStageSpec
final class SftpStageSpec extends BaseSftpSpec with CommonFtpStageSpec
final class FtpsStageSpec extends BaseFtpsSpec with CommonFtpStageSpec {
  setAuthValue("TLS")
  setUseImplicit(false)
}

final class RawKeySftpSourceSpec extends BaseSftpSpec with CommonFtpStageSpec {
  override val settings = SftpSettings(
    InetAddress.getByName("localhost")
  ).withPort(getPort)
    .withCredentials(FtpCredentials.create("different user and password", "will fail password auth"))
    .withStrictHostKeyChecking(false)
    .withSftpIdentity(
      SftpIdentity.createRawSftpIdentity(
        Files.readAllBytes(Paths.get(getClientPrivateKeyFile.getPath)),
        ClientPrivateKeyPassphrase
      )
    )
}

final class KeyFileSftpSourceSpec extends BaseSftpSpec with CommonFtpStageSpec {
  override protected def extraWaitForStageShutdown(): Unit = Thread.sleep(10 * 1000)

  override val settings = SftpSettings(
    InetAddress.getByName("localhost")
  ).withPort(getPort)
    .withCredentials(FtpCredentials.create("different user and password", "will fail password auth"))
    .withStrictHostKeyChecking(false)
    .withSftpIdentity(
      SftpIdentity.createFileSftpIdentity(getClientPrivateKeyFile.getPath, ClientPrivateKeyPassphrase)
    )
}

final class StrictHostCheckingSftpSourceSpec extends BaseSftpSpec with CommonFtpStageSpec {
  override val settings = SftpSettings(
    InetAddress.getByName("localhost")
  ).withPort(getPort)
    .withCredentials(FtpCredentials.create("different user and password", "will fail password auth"))
    .withStrictHostKeyChecking(true)
    .withKnownHosts(getKnownHostsFile.getPath)
    .withSftpIdentity(
      SftpIdentity.createFileSftpIdentity(getClientPrivateKeyFile.getPath, ClientPrivateKeyPassphrase)
    )
}

trait CommonFtpStageSpec extends BaseSpec with Eventually {

  implicit val system = getSystem
  implicit val mat = getMaterializer
  implicit val defaultPatience =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(600, Millis))

  "FtpBrowserSource" should {
    "list all files from root" in assertAllStagesStopped {
      val basePath = ""
      generateFiles(30, 10, basePath)
      val probe =
        listFiles(basePath).filter(_.isFile).toMat(TestSink.probe)(Keep.right).run()
      probe.request(40).expectNextN(30)
      probe.expectComplete()
    }

    "list all files from non-root" in assertAllStagesStopped {
      val basePath = "/foo"
      generateFiles(30, 10, basePath)
      val probe =
        listFiles(basePath).filter(_.isFile).toMat(TestSink.probe)(Keep.right).run()
      probe.request(40).expectNextN(30)
      probe.expectComplete()
    }

    "list only first level from base path" in assertAllStagesStopped {
      val basePath = "/foo"
      generateFiles(30, 10, basePath)
      val probe =
        listFilesWithFilter(basePath, f => false).toMat(TestSink.probe)(Keep.right).run()
      probe.request(40).expectNextN(12) // 9 files, 3 directories
      probe.expectComplete()

    }

    "list only go into second page" in assertAllStagesStopped {
      val basePath = "/foo"
      generateFiles(30, 10, basePath)
      val probe =
        listFilesWithFilter(basePath, f => f.name.contains("1")).toMat(TestSink.probe)(Keep.right).run()
      probe.request(40).expectNextN(22) // 9 files in root, 3 directories, 10 files in dir_1
      probe.expectComplete()

    }

    "list all files in sparse directory tree" in assertAllStagesStopped {
      val deepDir = "/foo/bar/baz/foobar"
      val basePath = ""
      generateFiles(1, -1, deepDir)
      val probe =
        listFiles(basePath).toMat(TestSink.probe)(Keep.right).run()
      probe.request(10).expectNextN(5) // foo, bar, baz, foobar, and sample_1 = 5 files
      probe.expectComplete()
    }

    "retrieve relevant file attributes" in assertAllStagesStopped {
      val fileName = "sample"
      val basePath = "/"

      putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)

      val timestamp = System.currentTimeMillis().millis

      val files = listFiles(basePath).runWith(Sink.seq).futureValue

      files should have size 1
      inside(files.head) {
        case FtpFile(actualFileName, actualPath, isDirectory, size, lastModified, perms) ⇒
          actualFileName shouldBe fileName
          actualPath shouldBe s"$basePath$fileName"
          isDirectory shouldBe false
          size shouldBe getLoremIpsum.length
          timestamp - lastModified.millis should be < 1.minute
          perms should contain allOf (PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
      }
    }
  }

  "FtpIOSource" should {
    "retrieve a file from path as a stream of bytes" in assertAllStagesStopped {
      val fileName = "sample_io"
      putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)
      val (result, probe) =
        retrieveFromPath(s"/$fileName").toMat(TestSink.probe)(Keep.both).run()
      probe.request(100).expectNextOrComplete()

      val expectedNumOfBytes = getLoremIpsum.getBytes().length
      result.futureValue shouldBe IOResult.createSuccessful(expectedNumOfBytes)
    }

    "retrieve a file from path with offset as a stream of bytes" in assertAllStagesStopped {
      val fileName = "sample_io"
      val offset = 10L
      putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)
      val (result, probe) =
        retrieveFromPathWithOffset(s"/$fileName", offset).toMat(TestSink.probe)(Keep.both).run()
      probe.request(100).expectNextOrComplete()

      val expectedNumOfBytes = getLoremIpsum.getBytes().length - offset
      result.futureValue shouldBe IOResult.createSuccessful(expectedNumOfBytes)
    }

    "retrieve a bigger file (~2 MB) from path as a stream of bytes" in assertAllStagesStopped {
      val fileName = "sample_bigger_file"
      val fileContents = new Array[Byte](2000020)
      Random.nextBytes(fileContents)
      putFileOnFtpWithContents(FtpBaseSupport.FTP_ROOT_DIR, fileName, fileContents)
      val (result, probe) = retrieveFromPath(s"/$fileName").toMat(TestSink.probe)(Keep.both).run()
      probe.request(1000).expectNextOrComplete()

      val expectedNumOfBytes = fileContents.length
      result.futureValue shouldBe IOResult.createSuccessful(expectedNumOfBytes)
    }

    "retrieve a bigger file (~2 MB) from path with offset as a stream of bytes" in assertAllStagesStopped {
      val fileName = "sample_bigger_file"
      val fileContents = new Array[Byte](2000020)
      val offset = 1000010L
      Random.nextBytes(fileContents)
      putFileOnFtpWithContents(FtpBaseSupport.FTP_ROOT_DIR, fileName, fileContents)
      val (result, probe) = retrieveFromPathWithOffset(s"/$fileName", offset).toMat(TestSink.probe)(Keep.both).run()
      probe.request(1000).expectNextOrComplete()

      val expectedNumOfBytes = fileContents.length - offset
      result.futureValue shouldBe IOResult.createSuccessful(expectedNumOfBytes)
    }
  }

  "FtpBrowserSource & FtpIOSource" should {
    "work together retrieving a list of files" in assertAllStagesStopped {
      val basePath = ""
      val numOfFiles = 10
      generateFiles(numOfFiles, numOfFiles, basePath)
      val probe = listFiles(basePath)
        .filter(_.isFile)
        .mapAsyncUnordered(1)(file => retrieveFromPath(file.path).to(Sink.ignore).run())
        .toMat(TestSink.probe)(Keep.right)
        .run()
      val result = probe.request(numOfFiles + 1).expectNextN(numOfFiles)
      probe.expectComplete()

      val expectedNumOfBytes = getLoremIpsum.getBytes().length * numOfFiles
      val total = result.map(_.count).sum
      total shouldBe expectedNumOfBytes
    }
  }

  "FTPIOSink" when {
    val fileName = "sample_io"

    "no file is already present at the target location" should {
      "create a new file from the provided stream of bytes regardless of the append mode" in assertAllStagesStopped {
        List(true, false).foreach { mode ⇒
          val result = Source.single(ByteString(getLoremIpsum)).runWith(storeToPath(s"/$fileName", mode)).futureValue

          val expectedNumOfBytes = getLoremIpsum.getBytes().length
          result shouldBe IOResult.createSuccessful(expectedNumOfBytes)

          val storedContents = getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName)
          storedContents shouldBe getLoremIpsum.getBytes
        }
      }
    }

    "a file is already present at the target location" should {

      val reversedLoremIpsum = getLoremIpsum.reverse
      val expectedNumOfBytes = reversedLoremIpsum.length

      "overwrite it when not in append mode" in assertAllStagesStopped {
        putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)

        val result =
          Source.single(ByteString(reversedLoremIpsum)).runWith(storeToPath(s"/$fileName", append = false)).futureValue

        result shouldBe IOResult.createSuccessful(expectedNumOfBytes)

        val storedContents = getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName)
        storedContents shouldBe reversedLoremIpsum.getBytes
      }

      "append to its contents when in append mode" in assertAllStagesStopped {
        putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)

        val result =
          Source.single(ByteString(reversedLoremIpsum)).runWith(storeToPath(s"/$fileName", append = true)).futureValue

        result shouldBe IOResult.createSuccessful(expectedNumOfBytes)

        val storedContents = getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName)

        storedContents shouldBe getLoremIpsum.getBytes ++ reversedLoremIpsum.getBytes
      }
    }
  }

  it should {
    "write a bigger file (~2 MB) to a path from a stream of bytes" in assertAllStagesStopped {
      val fileName = "sample_bigger_file"
      val fileContents = new Array[Byte](2000020)
      Random.nextBytes(fileContents)

      val result = Source[Byte](fileContents.toList)
        .grouped(8192)
        .map(s ⇒ ByteString.apply(s.toArray))
        .runWith(storeToPath(s"/$fileName", append = false))
        .futureValue

      val expectedNumOfBytes = fileContents.length
      result shouldBe IOResult.createSuccessful(expectedNumOfBytes)

      val storedContents = getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName)
      storedContents shouldBe fileContents
    }

    "fail and report the exception in the result status if upstream fails" in assertAllStagesStopped {
      val fileName = "sample_io_upstream"
      val brokenSource = Source(10.to(0, -1)).map(x ⇒ ByteString(10 / x))

      val result = brokenSource.runWith(storeToPath(s"/$fileName", append = false)).futureValue

      result.status.failed.get shouldBe a[ArithmeticException]
      extraWaitForStageShutdown()
    }

    "fail and report the exception in the result status if connection fails" in { // TODO Fails too often on Travis: assertAllStagesStopped {
      def waitForUploadToStart(fileName: String) =
        eventually {
          noException should be thrownBy getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName)
          getFtpFileContents(FtpBaseSupport.FTP_ROOT_DIR, fileName).length shouldBe >(0)
        }

      val fileName = "sample_io_connection"
      val infiniteSource = Source.repeat(ByteString(0x00))

      val future = infiniteSource.runWith(storeToPath(s"/$fileName", append = false))
      waitForUploadToStart(fileName)
      stopServer()
      val result = future.futureValue
      startServer()

      result.status.failed.get shouldBe a[Exception]
    }
  }

  "FtpRemoveSink" should {
    "remove a file" in { // TODO Fails too often on Travis: assertAllStagesStopped {
      val fileName = "sample_io"
      putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)

      val source = listFiles("/")

      val result = source.runWith(remove()).futureValue

      result shouldBe IOResult.createSuccessful(1)

      fileExists(FtpBaseSupport.FTP_ROOT_DIR, fileName) shouldBe false
      extraWaitForStageShutdown()
    }
  }

  "FtpMoveSink" should {
    "move a file" in { // TODO Fails too often on Travis: assertAllStagesStopped {
      val fileName = "sample_io"
      val fileName2 = "sample_io2"
      putFileOnFtp(FtpBaseSupport.FTP_ROOT_DIR, fileName)

      val source = listFiles("/")

      val result = source.runWith(move(_ => fileName2)).futureValue

      result shouldBe IOResult.createSuccessful(1)

      fileExists(FtpBaseSupport.FTP_ROOT_DIR, fileName) shouldBe false
      fileExists(FtpBaseSupport.FTP_ROOT_DIR, fileName2) shouldBe true
      extraWaitForStageShutdown()
    }
  }
}
