/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.ftp
import java.net.InetAddress

import akka.NotUsed
import akka.stream.IOResult
import akka.stream.alpakka.ftp.scaladsl.Ftps
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString

import scala.concurrent.Future

trait BaseFtpsSpec extends FtpsSupportImpl with BaseSpec {

  val settings = FtpsSettings(
    InetAddress.getByName("localhost")
  ).withPort(getPort)
    .withBinary(true)
    .withPassiveMode(true)

  protected def listFiles(basePath: String): Source[FtpFile, NotUsed] =
    Ftps.ls(basePath, settings)

  protected def listFilesWithFilter(basePath: String, branchSelector: FtpFile => Boolean): Source[FtpFile, NotUsed] =
    Ftps.ls(basePath, settings, branchSelector)

  protected def retrieveFromPath(path: String): Source[ByteString, Future[IOResult]] =
    Ftps.fromPath(path, settings)

  protected def retrieveFromPathWithOffset(path: String, offset: Long): Source[ByteString, Future[IOResult]] =
    Ftps.fromPath(path, settings, offset = offset)

  protected def storeToPath(path: String, append: Boolean): Sink[ByteString, Future[IOResult]] =
    Ftps.toPath(path, settings, append)

  protected def remove(): Sink[FtpFile, Future[IOResult]] =
    Ftps.remove(settings)

  protected def move(destinationPath: FtpFile => String): Sink[FtpFile, Future[IOResult]] =
    Ftps.move(destinationPath, settings)
}
