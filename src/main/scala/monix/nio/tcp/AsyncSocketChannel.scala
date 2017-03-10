/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.nio.tcp

import java.net.{ InetSocketAddress, StandardSocketOptions }
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{ AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler }
import java.util.concurrent.TimeUnit

import monix.eval.{ Callback, Task }
import monix.execution.Scheduler
import monix.nio.internal.ExecutorServiceWrapper

import scala.concurrent.duration.Duration
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
 * An asynchronous channel for reading, writing, and manipulating a TCP socket.
 *
 * On the JVM this is a wrapper around
 * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousSocketChannel.html java.nio.channels.AsynchronousSocketChannel]]
 * (class available since Java 7 for doing async I/O on sockets).
 *
 * @example {{{
 *   val asyncSocketChannel = AsyncSocketChannel()
 *
 *   val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))
 *
 *   val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
 *   val writeF = connectF.flatMap(_ => asyncSocketChannel.write(bytes, None))
 *
 *   writeF.onComplete {
 *     case Success(nr) =>
 *       println(f"Bytes written: $nr%d")
 *
 *    case Failure(exc) =>
 *       println(s"ERR: $exc")
 *   }
 * }}}
 *
 * @define callbackDesc is the callback to be called with the result, once
 *         this asynchronous operation is complete
 *
 * @define connectDesc Connects this channel.
 *
 * @define remoteDesc the remote address to which this channel is to be connected
 *
 * @define localAddressDesc Asks the socket address that this channel's socket is bound to
 *
 * @define remoteAddressDesc Asks the remote address to which this channel's socket is connected
 *
 * @define readDesc Reads a sequence of bytes from this channel into the given buffer
 *
 * @define readDestDesc is the buffer holding the bytes read on completion
 *
 * @define readReturnDesc the number of bytes read or -1 if no bytes could be read
 *         because the channel has reached end-of-stream
 *
 * @define writeDesc Writes a sequence of bytes to this channel from the given buffer
 *
 * @define writeSrcDesc is the buffer holding the sequence of bytes to write
 *
 * @define writeReturnDesc the number of bytes that were written
 *
 * @define timeout an optional maximum time for the I/O operation to complete
 *
 * @define stopReadingDesc Indicates that this channel will not read more data
 *         - end-of-stream indication
 *
 * @define stopWritingDesc Indicates that this channel will not write more data
 *         - end-of-stream indication
 */
abstract class AsyncSocketChannel extends AutoCloseable {

  /**
   * $connectDesc
   *
   * @param remote $remoteDesc
   * @param cb $callbackDesc
   */
  def connect(remote: InetSocketAddress, cb: Callback[Unit]): Unit

  /**
   * $connectDesc
   *
   * @param remote $remoteDesc
   */
  def connect(remote: InetSocketAddress): Future[Unit] = {
    val p = Promise[Unit]()
    connect(remote, Callback.fromPromise(p))
    p.future
  }

  /**
   * $connectDesc
   *
   * @param remote $remoteDesc
   */
  def connectL(remote: InetSocketAddress): Task[Unit] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      connect(remote, Callback.async(cb))
    }

  /**
   * $localAddressDesc
   */
  def localAddress(): Option[InetSocketAddress]

  /**
   * $remoteAddressDesc
   */
  def remoteAddress(): Option[InetSocketAddress]

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param cb $callbackDesc . For this method it signals $readReturnDesc
   * @param timeout $timeout
   */
  def read(dst: ByteBuffer, cb: Callback[Int], timeout: Option[Duration] = None): Unit

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param timeout $timeout
   *
   * @return $readReturnDesc
   */
  def read(dst: ByteBuffer, timeout: Option[Duration]): Future[Int] = {
    val p = Promise[Int]()
    read(dst, Callback.fromPromise(p), timeout)
    p.future
  }

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param timeout $timeout
   *
   * @return $readReturnDesc
   */
  def readL(dst: ByteBuffer, timeout: Option[Duration] = None): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      read(dst, Callback.async(cb), timeout)
    }

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param cb $callbackDesc . For this method it signals $writeReturnDesc
   * @param timeout $timeout
   */
  def write(src: ByteBuffer, cb: Callback[Int], timeout: Option[Duration] = None): Unit

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param timeout $timeout
   *
   * @return $writeReturnDesc
   */
  def write(src: ByteBuffer, timeout: Option[Duration]): Future[Int] = {
    val p = Promise[Int]()
    write(src, Callback.fromPromise(p), timeout)
    p.future
  }

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param timeout $timeout
   *
   * @return $writeReturnDesc
   */
  def writeL(src: ByteBuffer, timeout: Option[Duration] = None): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      write(src, Callback.async(cb), timeout)
    }

  /**
   * $stopReadingDesc
   */
  def stopReading(): Unit

  /**
   * $stopWritingDesc
   */
  def stopWriting(): Unit
}

object AsyncSocketChannel {
  /**
   * Opens a socket channel for the given [[java.net.InetSocketAddress]]
   *
   * @param reuseAddress [[java.net.ServerSocket#setReuseAddress]]
   * @param sendBufferSize [[java.net.Socket#setSendBufferSize]]
   * @param receiveBufferSize [[java.net.Socket#setReceiveBufferSize]] [[java.net.ServerSocket#setReceiveBufferSize]]
   * @param keepAlive [[java.net.Socket#setKeepAlive]]
   * @param noDelay [[java.net.Socket#setTcpNoDelay]]
   *
   * @param s is the `Scheduler` used for asynchronous computations
   *
   * @return an [[monix.nio.tcp.AsyncSocketChannel]] instance for handling reads and writes.
   */
  def apply(
    reuseAddress: Boolean = true,
    sendBufferSize: Int = 256 * 1024,
    receiveBufferSize: Int = 256 * 1024,
    keepAlive: Boolean = false,
    noDelay: Boolean = false
  )(implicit s: Scheduler): AsyncSocketChannel = {

    NewIOImplementation(reuseAddress, sendBufferSize, receiveBufferSize, keepAlive, noDelay)
  }

  private final case class NewIOImplementation(
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false
  )(implicit scheduler: Scheduler) extends AsyncSocketChannel {

    private[this] lazy val asyncSocketChannel: Either[Throwable, AsynchronousSocketChannel] =
      try {
        val ag = AsynchronousChannelGroup.withThreadPool(ExecutorServiceWrapper(scheduler))
        val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(ag)

        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)

        Right(ch)
      } catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          Left(exc)
      }

    override def connect(remote: InetSocketAddress, cb: Callback[Unit]): Unit = {
      val handler = new CompletionHandler[Void, Null] {
        override def completed(result: Void, attachment: Null) =
          cb.onSuccess(())
        override def failed(exc: Throwable, attachment: Null) =
          cb.onError(exc)
      }
      asyncSocketChannel.fold(_ => (), c => try c.connect(remote, null, handler) catch {
        case NonFatal(exc) =>
          cb.onError(exc)
      })
    }

    override def localAddress(): Option[InetSocketAddress] = {
      asyncSocketChannel.fold(_ => None, c => try Option(c.getLocalAddress.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def remoteAddress(): Option[InetSocketAddress] = {
      asyncSocketChannel.fold(_ => None, c => try Option(c.getRemoteAddress.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def read(dst: ByteBuffer, cb: Callback[Int], timeout: Option[Duration]): Unit = {
      val handler = new CompletionHandler[Integer, Null] {
        override def completed(result: Integer, attachment: Null) =
          cb.onSuccess(result)
        override def failed(exc: Throwable, attachment: Null) =
          cb.onError(exc)
      }
      asyncSocketChannel.fold(_ => (), { c =>
        try {
          c.read(
            dst,
            timeout.map(_.length).getOrElse(0l),
            timeout.map(_.unit).getOrElse(TimeUnit.MILLISECONDS),
            null,
            handler
          )
        } catch {
          case NonFatal(exc) =>
            cb.onError(exc)
        }
      })
    }

    override def write(src: ByteBuffer, cb: Callback[Int], timeout: Option[Duration]): Unit = {
      val handler = new CompletionHandler[Integer, Null] {
        override def completed(result: Integer, attachment: Null) =
          cb.onSuccess(result)
        override def failed(exc: Throwable, attachment: Null) =
          cb.onError(exc)
      }
      asyncSocketChannel.fold(_ => (), { c =>
        try {
          c.write(
            src,
            timeout.map(_.length).getOrElse(0l),
            timeout.map(_.unit).getOrElse(TimeUnit.MILLISECONDS),
            null,
            handler
          )
        } catch {
          case NonFatal(exc) =>
            cb.onError(exc)
        }
      })
    }

    override def stopReading(): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.shutdownInput() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }

    override def stopWriting(): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.shutdownOutput() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }

    override def close(): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.close() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }
  }
}
