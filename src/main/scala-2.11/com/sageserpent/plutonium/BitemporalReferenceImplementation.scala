package com.sageserpent.plutonium

/**
 * Created by Gerard on 20/07/2015.
 */
class BitemporalReferenceImplementation[Raw] extends Bitemporal[Raw]{
  override def filter(predicate: (Raw) => Boolean): Bitemporal[Raw] = ???

  override def flatMap[Raw2](stage: (Raw) => Bitemporal[Raw2]): Bitemporal[Raw2] = ???

  override def map[Raw2](transform: (Raw) => Raw2): Bitemporal[Raw2] = ???
}