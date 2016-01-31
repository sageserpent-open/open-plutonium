package com.sageserpent.plutonium

/**
  * Created by Gerard on 10/01/2016.
  */
trait BestPatchSelectionImplementation extends BestPatchSelection {
  override def apply(relatedPatches: Seq[AbstractPatch[Identified]]): AbstractPatch[Identified] = relatedPatches.last
}
