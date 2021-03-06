package com.sageserpent

import scalaz.ApplicativePlus

package object plutonium {
  type EventId = Any

  implicit val applicativePlus: ApplicativePlus[Bitemporal] =
    new ApplicativePlus[Bitemporal] {
      override def point[A](a: => A): Bitemporal[A] = Bitemporal(a)

      override def empty[A]: Bitemporal[A] = Bitemporal.none

      override def plus[A](a: Bitemporal[A],
                           b: => Bitemporal[A]): Bitemporal[A] = a plus b

      override def ap[A, B](fa: => Bitemporal[A])(
          f: => Bitemporal[(A) => B]): Bitemporal[B] = fa ap f
    }
}
