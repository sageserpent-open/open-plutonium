package com.sageserpent.plutonium

/**
  * Created by Gerard on 10/10/2015.
  */
abstract class MoreSpecificFooHistory extends FooHistory {
  override def property1_=(data: String): Unit = {
    recordDatum(data)
  }
}
