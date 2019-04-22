/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.ftp.impl

import java.io.{IOException, InputStream, OutputStream}
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

import akka.annotation.InternalApi
import akka.stream.alpakka.ftp.FtpFile
import org.apache.commons.net.ftp.{FTPClient, FTPFile}

import scala.collection.immutable
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi
private[ftp] trait CommonFtpOperations {
  type Handler = FTPClient

  def listFiles(basePath: String, handler: Handler): immutable.Seq[FtpFile] = {
    val path = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else if (basePath == "/") "" else basePath
    handler
      .listFiles(path)
      .collect {
        case file: FTPFile if file.getName != "." && file.getName != ".." =>
          FtpFile(
            file.getName,
            if (java.io.File.separatorChar == '\\')
              Paths.get(s"$path/${file.getName}").normalize.toString.replace('\\', '/')
            else
              Paths.get(s"$path/${file.getName}").normalize.toString,
            file.isDirectory,
            file.getSize,
            file.getTimestamp.getTimeInMillis,
            getPosixFilePermissions(file)
          )
      }
      .toVector
  }

  private def getPosixFilePermissions(file: FTPFile) =
    Map(
      PosixFilePermission.OWNER_READ → file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.OWNER_WRITE → file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.OWNER_EXECUTE → file.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION),
      PosixFilePermission.GROUP_READ → file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.GROUP_WRITE → file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.GROUP_EXECUTE → file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION),
      PosixFilePermission.OTHERS_READ → file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION),
      PosixFilePermission.OTHERS_WRITE → file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION),
      PosixFilePermission.OTHERS_EXECUTE → file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
    ).collect {
      case (perm, true) ⇒ perm
    }.toSet

  def listFiles(handler: Handler): immutable.Seq[FtpFile] = listFiles("", handler)

  def retrieveFileInputStream(name: String, handler: Handler, offset: Long): Try[InputStream] = Try {
    handler.setRestartOffset(offset)
    val is = handler.retrieveFileStream(name)
    if (is != null) is else throw new IOException(s"$name: No such file or directory")
  }

  def storeFileOutputStream(name: String, handler: Handler, append: Boolean): Try[OutputStream] = Try {
    val os = if (append) handler.appendFileStream(name) else handler.storeFileStream(name)
    if (os != null) os else throw new IOException(s"Could not write to $name")
  }

  def move(fromPath: String, destinationPath: String, handler: Handler): Unit =
    handler.rename(fromPath, destinationPath)

  def remove(path: String, handler: Handler): Unit =
    handler.deleteFile(path)
}
