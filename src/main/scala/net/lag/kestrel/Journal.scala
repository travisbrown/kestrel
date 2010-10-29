/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.io._
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import com.twitter.xrayspecs.Time
import net.lag.configgy.{Config, ConfigMap}
import net.lag.logging.Logger


// returned from journal replay
abstract class JournalItem()
object JournalItem {
  case class Add(item: QItem) extends JournalItem
  case object Remove extends JournalItem
  case object RemoveTentative extends JournalItem
  case class SavedXid(xid: Int) extends JournalItem
  case class Unremove(xid: Int) extends JournalItem
  case class ConfirmRemove(xid: Int) extends JournalItem
  case object EndOfFile extends JournalItem
}


/**
 * Codes for working with the journal file for a PersistentQueue.
 */
class Journal(queuePath: String, queueName: String, syncJournal: => Boolean, multifile: => Boolean) {
  private val log = Logger.get

  private val queueFile = new File(queuePath, queueName)

  private var writer: FileChannel = null
  private var reader: Option[FileChannel] = None
  private var readerFilename: Option[String] = None
  private var replayer: Option[FileChannel] = None

  var size: Long = 0

  // small temporary buffer for formatting operations into the journal:
  private val buffer = new Array[Byte](16)
  private val byteBuffer = ByteBuffer.wrap(buffer)
  byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

  private val CMD_ADD = 0
  private val CMD_REMOVE = 1
  private val CMD_ADDX = 2
  private val CMD_REMOVE_TENTATIVE = 3
  private val CMD_SAVE_XID = 4
  private val CMD_UNREMOVE = 5
  private val CMD_CONFIRM_REMOVE = 6
  private val CMD_ADD_XID = 7


  def this(fullPath: String, syncJournal: => Boolean) =
    this(new File(fullPath).getParent(), new File(fullPath).getName(), syncJournal, false)

  private def open(file: File): Unit = {
    writer = new FileOutputStream(file, true).getChannel
  }

  def open(): Unit = {
    open(queueFile)
  }

  def roll(xid: Int, openItems: Seq[QItem], queue: Iterable[QItem]): Unit = {
    writer.close
    val tmpFile = new File(queuePath + "~~" + Time.now.inMilliseconds)
    open(tmpFile)
    dump(xid, openItems, queue)
    writer.close
    tmpFile.renameTo(queueFile)
    open
  }

  def dump(xid: Int, openItems: Seq[QItem], queue: Iterable[QItem]) {
    size = 0
    for (item <- openItems) {
      addWithXid(item)
      removeTentative(false)
    }
    saveXid(xid)
    for (item <- queue) {
      add(false, item)
    }
    if (syncJournal) writer.force(false)
  }

  def close(): Unit = {
    writer.close
    reader.foreach { _.close }
    reader = None
    readerFilename = None
  }

  def erase(): Unit = {
    try {
      close()
      queueFile.delete
    } catch {
      case _ =>
    }
  }

  def inReadBehind(): Boolean = reader.isDefined

  def isReplaying(): Boolean = replayer.isDefined

  private def add(allowSync: Boolean, item: QItem): Unit = {
    val blob = ByteBuffer.wrap(item.pack())
    size += write(false, CMD_ADDX.toByte, blob.limit)
    do {
      writer.write(blob)
    } while (blob.position < blob.limit)
    if (allowSync && syncJournal) writer.force(false)
    size += blob.limit
    checkRotate()
  }

  def add(item: QItem): Unit = add(true, item)

  // used only to list pending transactions when recreating the journal.
  private def addWithXid(item: QItem) = {
    val blob = ByteBuffer.wrap(item.pack())

    // only called from roll(), so the journal does not need to be synced after a write.
    size += write(false, CMD_ADD_XID.toByte, item.xid, blob.limit)
    do {
      writer.write(blob)
    } while (blob.position < blob.limit)
    size += blob.limit
  }

  def remove() = {
    size += write(true, CMD_REMOVE.toByte)
  }

  private def removeTentative(allowSync: Boolean): Unit = {
    size += write(allowSync, CMD_REMOVE_TENTATIVE.toByte)
  }

  def removeTentative(): Unit = removeTentative(true)

  private def saveXid(xid: Int) = {
    // only called from roll(), so the journal does not need to be synced after a write.
    size += write(false, CMD_SAVE_XID.toByte, xid)
  }

  def unremove(xid: Int) = {
    size += write(true, CMD_UNREMOVE.toByte, xid)
  }

  def confirmRemove(xid: Int) = {
    size += write(true, CMD_CONFIRM_REMOVE.toByte, xid)
  }

  def startReadBehind(): Unit = {
    val pos = if (replayer.isDefined) replayer.get.position else writer.position
    val rj = new FileInputStream(queueFile).getChannel
    rj.position(pos)
    reader = Some(rj)
    readerFilename = Some(queueName)
  }

  // not tail recursive, but should only recurse once.
  def fillReadBehind(f: QItem => Unit): Unit = {
    val pos = if (replayer.isDefined) replayer.get.position else writer.position
    reader.foreach { rj =>
      if (rj.position == pos && readerFilename.get == queueName) {
        // we've caught up.
        rj.close
        reader = None
        readerFilename = None
      } else {
        readJournalEntry(rj) match {
          case (JournalItem.Add(item), _) =>
            f(item)
          case (JournalItem.EndOfFile, _) =>
            // move to next file and try again.
            readerFilename = Journal.journalAfter(new File(queuePath), queueName, readerFilename.get)
            reader = Some(new FileInputStream(new File(queuePath, readerFilename.get)).getChannel)
            fillReadBehind(f)
            // FIXME: send actor signal to pack queue files!
          case (_, _) =>
        }
      }
    }
  }

  def replay(name: String)(f: JournalItem => Unit): Unit = {
    Journal.journalsForQueue(new File(queuePath), queueName).foreach { filename =>
      replayFile(name, new File(queuePath, filename).getCanonicalPath)(f)
    }
  }

  def replayFile(name: String, filename: String)(f: JournalItem => Unit): Unit = {
    size = 0
    var lastUpdate = 0L
    val TEN_MB = 10L * 1024 * 1024
    try {
      val in = new FileInputStream(filename).getChannel
      try {
        replayer = Some(in)
        var done = false
        do {
          readJournalEntry(in) match {
            case (JournalItem.EndOfFile, _) => done = true
            case (x, itemsize) =>
              size += itemsize
              f(x)
              if (size / TEN_MB > lastUpdate) {
                lastUpdate = size / TEN_MB
                log.info("Continuing to read '%s' journal (%s); %d MB so far...", name, filename, lastUpdate * 10)
              }
          }
        } while (!done)
      } catch {
        case e: BrokenItemException =>
          log.error(e, "Exception replaying journal for '%s': %s", name, filename)
          log.error("DATA MAY HAVE BEEN LOST! Truncated entry will be deleted.")
          truncateJournal(e.lastValidPosition)
      }
    } catch {
      case e: FileNotFoundException =>
        log.info("No transaction journal for '%s'; starting with empty queue.", name)
      case e: IOException =>
        log.error(e, "Exception replaying journal for '%s': %s", name, filename)
        log.error("DATA MAY HAVE BEEN LOST!")
        // this can happen if the server hardware died abruptly in the middle
        // of writing a journal. not awesome but we should recover.
    }
    replayer = None
  }

  private def truncateJournal(position: Long) {
    val trancateWriter = new FileOutputStream(queueFile, true).getChannel
    try {
      trancateWriter.truncate(position)
    } finally {
      trancateWriter.close()
    }
  }

  def readJournalEntry(in: FileChannel): (JournalItem, Int) = {
    byteBuffer.rewind
    byteBuffer.limit(1)
    val lastPosition = in.position
    var x: Int = 0
    do {
      x = in.read(byteBuffer)
    } while (byteBuffer.position < byteBuffer.limit && x >= 0)

    if (x < 0) {
      (JournalItem.EndOfFile, 0)
    } else {
      try {
        buffer(0) match {
          case CMD_ADD =>
            val data = readBlock(in)
            (JournalItem.Add(QItem.unpackOldAdd(data)), 5 + data.length)
          case CMD_REMOVE =>
            (JournalItem.Remove, 1)
          case CMD_ADDX =>
            val data = readBlock(in)
            (JournalItem.Add(QItem.unpack(data)), 5 + data.length)
          case CMD_REMOVE_TENTATIVE =>
            (JournalItem.RemoveTentative, 1)
          case CMD_SAVE_XID =>
            val xid = readInt(in)
            (JournalItem.SavedXid(xid), 5)
          case CMD_UNREMOVE =>
            val xid = readInt(in)
            (JournalItem.Unremove(xid), 5)
          case CMD_CONFIRM_REMOVE =>
            val xid = readInt(in)
            (JournalItem.ConfirmRemove(xid), 5)
          case CMD_ADD_XID =>
            val xid = readInt(in)
            val data = readBlock(in)
            val item = QItem.unpack(data)
            item.xid = xid
            (JournalItem.Add(item), 9 + data.length)
          case n =>
            throw new BrokenItemException(lastPosition, new IOException("invalid opcode in journal: " + n.toInt + " at position " + in.position))
        }
      } catch {
        case ex: IOException =>
          throw new BrokenItemException(lastPosition, ex)
      }
    }
  }

  def walk() = new tools.PythonIterator[(JournalItem, Int)] {
// FIXME
    val in = new FileInputStream(new File(queuePath, queueName)).getChannel
    def apply() = {
      readJournalEntry(in) match {
        case (JournalItem.EndOfFile, _) =>
          in.close()
          None
        case x =>
          Some(x)
      }
    }
  }

  private def readBlock(in: FileChannel): Array[Byte] = {
    val size = readInt(in)
    val data = new Array[Byte](size)
    val dataBuffer = ByteBuffer.wrap(data)
    var x: Int = 0
    do {
      x = in.read(dataBuffer)
    } while (dataBuffer.position < dataBuffer.limit && x >= 0)
    if (x < 0) {
      // we never expect EOF when reading a block.
      throw new IOException("Unexpected EOF")
    }
    data
  }

  private def readInt(in: FileChannel): Int = {
    byteBuffer.rewind
    byteBuffer.limit(4)
    var x: Int = 0
    do {
      x = in.read(byteBuffer)
    } while (byteBuffer.position < byteBuffer.limit && x >= 0)
    if (x < 0) {
      // we never expect EOF when reading an int.
      throw new IOException("Unexpected EOF")
    }
    byteBuffer.rewind
    byteBuffer.getInt()
  }

  private def write(allowSync: Boolean, items: Any*): Int = {
    byteBuffer.clear
    for (item <- items) item match {
      case b: Byte => byteBuffer.put(b)
      case i: Int => byteBuffer.putInt(i)
    }
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    if (allowSync && syncJournal) writer.force(false)
    byteBuffer.limit
  }

  def checkRotate() {
/*
    if (size > desiredSize) {
      make new file
    }

    possibly set readerFilename to the rotated file.
*/
  }
}

object Journal {
  def getQueueNamesFromFolder(path: File): Seq[String] = {
    path.list().filter { name =>
      !(name contains "~~")
    }.map { name =>
      name.split('.')(0)
    }
  }

  def orderedFilesForQueue(path: File, queueName: String): List[String] = {
    val totalFiles = path.list()
    totalFiles.filter { _ startsWith (queueName + ".") }.sortBy { name =>
      name.split('.')(1).toInt
    }.toList
  }

  def journalsForQueue(path: File, queueName: String): List[String] = {
    val timedFiles = orderedFilesForQueue(path, queueName)
    val fixedTimedFiles: List[String] = if (timedFiles.exists { _ endsWith ".pack" }) {
      // incomplete migration died after creating the combined file, before erasing the others.
      // finish the job.
      val doomed = timedFiles.takeWhile { f => !(f endsWith ".pack") }
      doomed.foreach { f => new File(path, f).delete() }
      val packedFile = timedFiles.find { _ endsWith ".pack" }.get
      val postPackedFile = packedFile.substring(0, packedFile.length - 5)
      new File(path, packedFile) renameTo new File(path, postPackedFile)
      List(postPackedFile) ++ (timedFiles.dropWhile { f => !(f endsWith ".pack") }.drop(1))
    } else {
      timedFiles
    }
    val currentFile = path.list().filter { _ == queueName }
    val rv = fixedTimedFiles ++ currentFile
    if (rv.size == 0) {
      List(queueName)
    } else {
      rv
    }
  }

  def journalsBefore(path: File, queueName: String, filename: String): Seq[String] = {
    journalsForQueue(path, queueName).takeWhile { _ != filename }
  }

  def journalAfter(path: File, queueName: String, filename: String): Option[String] = {
    journalsForQueue(path, queueName).dropWhile { _ != filename }.drop(1).headOption
  }
}
