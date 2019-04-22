/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.ftp.javadsl

import akka.NotUsed
import akka.stream.alpakka.ftp.impl._
import akka.stream.alpakka.ftp.{FtpFile, RemoteFileSettings}
import akka.stream.alpakka.ftp.impl.{FtpLike, FtpSourceFactory}
import akka.stream.IOResult
import akka.stream.javadsl.Source
import akka.stream.javadsl.Sink
import akka.stream.scaladsl.{Source => ScalaSource}
import akka.stream.scaladsl.{Sink => ScalaSink}
import akka.util.ByteString
import net.schmizz.sshj.SSHClient
import org.apache.commons.net.ftp.{FTPClient, FTPSClient}
import java.util.concurrent.CompletionStage
import java.util.function._

import scala.compat.java8.FunctionConverters._

sealed trait FtpApi[FtpClient] { _: FtpSourceFactory[FtpClient] =>

  /**
   * The refined [[RemoteFileSettings]] type.
   */
  type S <: RemoteFileSettings

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from the remote user `root` directory.
   * By default, `anonymous` credentials will be used.
   *
   * @param host FTP, FTPs or SFTP host
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(host: String): Source[FtpFile, NotUsed] =
    ls(host, basePath = "")

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from a base path.
   * By default, `anonymous` credentials will be used.
   *
   * @param host FTP, FTPs or SFTP host
   * @param basePath Base path from which traverse the remote file server
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(
      host: String,
      basePath: String
  ): Source[FtpFile, NotUsed] =
    ls(basePath, defaultSettings(host))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from the remote user `root` directory.
   *
   * @param host FTP, FTPs or SFTP host
   * @param username username
   * @param password password
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(
      host: String,
      username: String,
      password: String
  ): Source[FtpFile, NotUsed] =
    ls("", defaultSettings(host, Some(username), Some(password)))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from a base path.
   *
   * @param host FTP, FTPs or SFTP host
   * @param username username
   * @param password password
   * @param basePath Base path from which traverse the remote file server
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(
      host: String,
      username: String,
      password: String,
      basePath: String
  ): Source[FtpFile, NotUsed] =
    ls(basePath, defaultSettings(host, Some(username), Some(password)))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from a base path.
   *
   * @param basePath Base path from which traverse the remote file server
   * @param connectionSettings connection settings
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(
      basePath: String,
      connectionSettings: S
  ): Source[FtpFile, NotUsed] =
    ScalaSource.fromGraph(createBrowserGraph(basePath, connectionSettings, f => true)).asJava

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s from a base path.
   *
   * @param basePath Base path from which traverse the remote file server
   * @param connectionSettings connection settings
   * @param branchSelector a predicate for pruning the tree. Takes a remote folder and return true
   *                       if you want to enter that remote folder.
   *                       Default behaviour is full recursiv which is equivalent with calling this function
   *                       with [[ls(basePath,connectionSettings,f->true)]].
   *
   *                       Calling [[ls(basePath,connectionSettings,f->false)]] will emit only the files and folder in
   *                       non-recursive fashion
   *
   * @return A [[akka.stream.javadsl.Source Source]] of [[FtpFile]]s
   */
  def ls(basePath: String, connectionSettings: S, branchSelector: Predicate[FtpFile]): Source[FtpFile, NotUsed] =
    Source.fromGraph(createBrowserGraph(basePath, connectionSettings, asScalaFromPredicate(branchSelector)))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] from some file path.
   *
   * @param host FTP, FTPs or SFTP host
   * @param path the file path
   * @return A [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def fromPath(
      host: String,
      path: String
  ): Source[ByteString, CompletionStage[IOResult]] =
    fromPath(path, defaultSettings(host))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] from some file path.
   *
   * @param host FTP, FTPs or SFTP host
   * @param username username
   * @param password password
   * @param path the file path
   * @return A [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def fromPath(
      host: String,
      username: String,
      password: String,
      path: String
  ): Source[ByteString, CompletionStage[IOResult]] =
    fromPath(path, defaultSettings(host, Some(username), Some(password)))

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] from some file path.
   *
   * @param path the file path
   * @param connectionSettings connection settings
   * @return A [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def fromPath(
      path: String,
      connectionSettings: S
  ): Source[ByteString, CompletionStage[IOResult]] =
    fromPath(path, connectionSettings, DefaultChunkSize, 0L)

  /**
   * Java API: creates a [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] from some file path.
   *
   * @param path the file path
   * @param connectionSettings connection settings
   * @param chunkSize the size of transmitted [[akka.util.ByteString ByteString]] chunks
   * @param offset the offset into the remote file at which to start the transfer
   * @return A [[akka.stream.javadsl.Source Source]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def fromPath(
      path: String,
      connectionSettings: S,
      chunkSize: Int = DefaultChunkSize,
      offset: Long = 0L
  ): Source[ByteString, CompletionStage[IOResult]] = {
    import scala.compat.java8.FutureConverters._
    ScalaSource.fromGraph(createIOSource(path, connectionSettings, chunkSize, offset)).mapMaterializedValue(_.toJava).asJava
  }

  /**
   * Java API: creates a [[akka.stream.javadsl.Sink Sink]] of [[akka.util.ByteString ByteString]] to some file path.
   *
   * @param path the file path
   * @param connectionSettings connection settings
   * @param append append data if a file already exists, overwrite the file if not
   * @return A [[akka.stream.javadsl.Sink Sink]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def toPath(
      path: String,
      connectionSettings: S,
      append: Boolean
  ): Sink[ByteString, CompletionStage[IOResult]] = {
    import scala.compat.java8.FutureConverters._
    ScalaSink.fromGraph(createIOSink(path, connectionSettings, append)).mapMaterializedValue(_.toJava).asJava
  }

  /**
   * Java API: creates a [[akka.stream.javadsl.Sink Sink]] of [[akka.util.ByteString ByteString]] to some file path.
   * If a file already exists at the specified target path, it will get overwritten.
   *
   * @param path the file path
   * @param connectionSettings connection settings
   * @return A [[akka.stream.javadsl.Sink Sink]] of [[akka.util.ByteString ByteString]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def toPath(
      path: String,
      connectionSettings: S
  ): Sink[ByteString, CompletionStage[IOResult]] =
    toPath(path, connectionSettings, append = false)

  /**
   * Java API: creates a [[akka.stream.javadsl.Sink Sink]] of a [[FtpFile]] that moves a file to some file path.
   *
   * @param destinationPath a function that returns path to where the [[FtpFile]] is moved.
   * @param connectionSettings connection settings
   * @return A [[akka.stream.javadsl.Sink Sink]] of [[FtpFile]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def move(destinationPath: Function[FtpFile, String],
           connectionSettings: S): Sink[FtpFile, CompletionStage[IOResult]] = {
    import scala.compat.java8.FutureConverters._
    import scala.compat.java8.FunctionConverters._
    ScalaSink
      .fromGraph(createMoveSink(destinationPath.asScala, connectionSettings))
      .mapMaterializedValue(_.toJava)
      .asJava
  }

  /**
   * Java API: creates a [[akka.stream.javadsl.Sink Sink]] of a [[FtpFile]] that removes a file.
   *
   * @param connectionSettings connection settings
   * @return A [[akka.stream.javadsl.Sink Sink]] of [[FtpFile]] that materializes to a [[java.util.concurrent.CompletionStage CompletionStage]] of [[IOResult]]
   */
  def remove(connectionSettings: S): Sink[FtpFile, CompletionStage[IOResult]] = {
    import scala.compat.java8.FutureConverters._
    ScalaSink.fromGraph(createRemoveSink(connectionSettings)).mapMaterializedValue(_.toJava).asJava
  }

  protected[this] implicit def ftpLike: FtpLike[FtpClient, S]
}
class SftpApi extends FtpApi[SSHClient] with SftpSourceParams
object Ftp extends FtpApi[FTPClient] with FtpSourceParams
object Ftps extends FtpApi[FTPSClient] with FtpsSourceParams
object Sftp extends SftpApi {

  /**
   * Java API: creates a [[akka.stream.alpakka.ftp.javadsl.SftpApi]]
   *
   * @param customSshClient custom ssh client
   * @return A [[akka.stream.alpakka.ftp.javadsl.SftpApi]]
   */
  def create(customSshClient: SSHClient): SftpApi =
    new SftpApi {
      override val sshClient = customSshClient
    }
}
