package com.sageserpent.plutonium
import scala.reflect.runtime.universe._

/**
  * Created by Gerard on 03/04/2016.
  */
protected[plutonium] trait Recorder {
  type ItemReconstitutionData[Item2] = (Any, TypeTag[Item2])

  def itemReconstitutionData: ItemReconstitutionData[_]
}
