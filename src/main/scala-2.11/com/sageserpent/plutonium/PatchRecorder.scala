package com.sageserpent.plutonium

import java.lang.reflect.Method
import java.time.Instant

import net.sf.cglib.proxy.MethodProxy

import scalaz.std.option.optionSyntax._

/**
  * Created by Gerard on 09/01/2016.
  */

trait PatchRecorder {
  case class Patch(target: Any, method: Method, arguments: Array[AnyRef], methodProxy: MethodProxy)

  def whenLatestEventTookPlace: Option[Instant] = ???

  def recordPatchFromChange(when: Instant, patch:Patch): Unit = ???

  def recordPathFromObservation(when: Instant, patch: Patch): Unit = ???

  def recordAnnihilation(when: Instant, target: Any): Unit = ???

  def noteThatThereAreNoFollowingRecordings(): Unit = ???
}

trait PatchRecorderContract extends PatchRecorder {
  abstract override def recordPatchFromChange(when: Instant, patch:Patch): Unit = {
    require(whenLatestEventTookPlace.cata(some = !when.isAfter(_), none = true))
    super.recordPatchFromChange(when, patch)
  }

  abstract override def recordPathFromObservation(when: Instant, patch: Patch): Unit = {
    require(whenLatestEventTookPlace.cata(some = !when.isAfter(_), none = true))
    super.recordPathFromObservation(when, patch)
  }

  abstract override def recordAnnihilation(when: Instant, target: Any): Unit = {
    require(whenLatestEventTookPlace.cata(some = !when.isAfter(_), none = true))
    super.recordAnnihilation(when, target)
  }
}