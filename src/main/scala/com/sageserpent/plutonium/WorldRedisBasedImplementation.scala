package com.sageserpent.plutonium

import java.nio.ByteBuffer
import java.time.Instant
import java.util.{NoSuchElementException, UUID}

import com.esotericsoftware.kryo.Kryo
import com.lambdaworks.redis.RedisClient
import com.lambdaworks.redis.api.rx.RedisReactiveCommands
import com.lambdaworks.redis.codec.{ByteArrayCodec, RedisCodec, Utf8StringCodec}
import com.sageserpent.americium.{PositiveInfinity, Unbounded}
import com.sageserpent.plutonium.ItemExtensionApi.UniqueItemSpecification
import com.twitter.chill.{KryoPool, ScalaKryoInstantiator}
import io.netty.handler.codec.EncoderException
import rx.lang.scala.JavaConversions._
import rx.lang.scala.Observable

import scala.Ordering.Implicits._

object WorldRedisBasedImplementation {
  import UniqueItemSpecificationSerializationSupport.SpecialSerializer

  val redisNamespaceComponentSeparator = ":"

  val kryoPool = KryoPool.withByteArrayOutputStream(
    40,
    new ScalaKryoInstantiator().withRegistrar { (kryo: Kryo) =>
      kryo.register(classOf[UniqueItemSpecification], new SpecialSerializer)
    }
  )

  object redisCodecDelegatingKeysToStandardCodec
      extends RedisCodec[String, Any] {
    protected val stringKeyStringValueCodec = new Utf8StringCodec

    override def encodeKey(key: String): ByteBuffer =
      stringKeyStringValueCodec.encodeKey(key)

    override def decodeKey(bytes: ByteBuffer): String =
      stringKeyStringValueCodec.decodeKey(bytes)

    override def encodeValue(value: Any): ByteBuffer =
      ByteArrayCodec.INSTANCE.encodeValue(kryoPool.toBytesWithClass(value))

    override def decodeValue(bytes: ByteBuffer): Any =
      kryoPool.fromBytes(ByteArrayCodec.INSTANCE.decodeValue(bytes))
  }
}

class WorldRedisBasedImplementation(redisClient: RedisClient,
                                    identityGuid: String)
    extends WorldInefficientImplementationCodeFactoring {
  parentWorld =>

  import World._
  import WorldImplementationCodeFactoring._
  import WorldRedisBasedImplementation._

  var redisApi: RedisReactiveCommands[String, Any] = null

  setupRedisApi()

  private def setupRedisApi() = {
    redisApi =
      redisClient.connect(redisCodecDelegatingKeysToStandardCodec).reactive()
  }

  private def teardownRedisApi(): Unit = {
    redisApi.close()
  }

  val asOfsKey = s"${identityGuid}${redisNamespaceComponentSeparator}asOfs"

  val eventCorrectionsKeyPrefix =
    s"${identityGuid}${redisNamespaceComponentSeparator}eventCorrectionsFor${redisNamespaceComponentSeparator}"

  val eventIdsKey =
    s"${identityGuid}${redisNamespaceComponentSeparator}eventIds"

  def eventCorrectionsKeyFrom(eventId: EventId) =
    s"${eventCorrectionsKeyPrefix}${eventId}"

  override def nextRevision: Revision = nextRevisionObservable.toBlocking.first

  protected def nextRevisionObservable: Observable[Revision] =
    toScalaObservable(redisApi.llen(asOfsKey)) map (_.toInt)

  override def forkExperimentalWorld(scope: javaApi.Scope): World =
    new WorldRedisBasedImplementation(
      redisClient = parentWorld.redisClient,
      identityGuid =
        s"${parentWorld.identityGuid}-experimental-${UUID.randomUUID()}") {
      val baseWorld                            = parentWorld
      val numberOfRevisionsInCommon            = scope.nextRevision
      val cutoffWhenAfterWhichHistoriesDiverge = scope.when

      override protected def nextRevisionObservable: Observable[Revision] =
        super.nextRevisionObservable map (numberOfRevisionsInCommon + _)

      override protected def revisionAsOfsObservable
        : Observable[Array[Instant]] =
        for {
          revisionAsOfsFromBaseWorld <- baseWorld.revisionAsOfsObservable
          revisionAsOfsFromSuper     <- super.revisionAsOfsObservable
        } yield
          (revisionAsOfsFromBaseWorld take numberOfRevisionsInCommon) ++ revisionAsOfsFromSuper

      override protected def pertinentEventDatumsObservable(
          cutoffRevision: Revision,
          cutoffWhen: Unbounded[Instant],
          eventIdInclusion: EventIdInclusion)
        : Observable[(EventId, AbstractEventData)] = {
        val cutoffWhenForBaseWorld = cutoffWhen min cutoffWhenAfterWhichHistoriesDiverge
        if (cutoffRevision > numberOfRevisionsInCommon) for {
          eventIdsAndTheirDatums <- eventIdsAndTheirDatumsObservable(
            cutoffRevision,
            eventIdInclusion).toList
          eventIdsToBeExcluded = eventIdsAndTheirDatums.map(_._1).toSet
          eventIdAndItsDatum <- Observable
            .from(eventIdsAndTheirDatums filter {
              case (_, eventDatum) =>
                eventDatumComesWithinCutoff(eventDatum, cutoffWhen)
            }) merge baseWorld.pertinentEventDatumsObservable(
            numberOfRevisionsInCommon,
            cutoffWhenForBaseWorld,
            eventId =>
              !eventIdsToBeExcluded.contains(eventId) && eventIdInclusion(
                eventId))
        } yield eventIdAndItsDatum
        else
          baseWorld.pertinentEventDatumsObservable(cutoffRevision,
                                                   cutoffWhenForBaseWorld,
                                                   eventIdInclusion)
      }
    }

  override def revisionAsOfs: Array[Instant] =
    revisionAsOfsObservable.toBlocking.first

  protected def revisionAsOfsObservable: Observable[Array[Instant]] =
    toScalaObservable(redisApi.lrange(asOfsKey, 0, -1))
      .asInstanceOf[Observable[Instant]]
      .toArray

  override protected def eventTimeline(
      cutoffRevision: Revision): Seq[(Event, EventId)] =
    try {
      val eventDatumsObservable = for {
        _           <- toScalaObservable(redisApi.watch(asOfsKey))
        eventDatums <- pertinentEventDatumsObservable(cutoffRevision).toList
        transactionStart = toScalaObservable(redisApi.multi())
        // NASTY HACK: there needs to be at least one Redis command sent in a
        // transaction for the result of the 'exec' command to yield a difference
        // between an aborted transaction and a completed one. Yuk!
        transactionBody = toScalaObservable(redisApi.llen(asOfsKey))
        transactionEnd  = toScalaObservable(redisApi.exec())
        ((_, _), _) <- transactionStart zip transactionBody zip transactionEnd
      } yield eventDatums

      eventTimelineFrom(eventDatumsObservable.toBlocking.single)
    } catch {
      case exception: EncoderException =>
        recoverRedisApi
        throw exception.getCause

      case _: NoSuchElementException =>
        throw new RuntimeException(
          "Concurrent revision attempt detected in query.")
    }

  type EventIdInclusion = EventId => Boolean

  protected def pertinentEventDatumsObservable(
      cutoffRevision: Revision,
      cutoffWhen: Unbounded[Instant],
      eventIdInclusion: EventIdInclusion)
    : Observable[(EventId, AbstractEventData)] =
    eventIdsAndTheirDatumsObservable(cutoffRevision, eventIdInclusion) filter {
      case (_, eventDatum) =>
        eventDatumComesWithinCutoff(eventDatum, cutoffWhen)
    }

  private def eventDatumComesWithinCutoff(eventDatum: AbstractEventData,
                                          cutoffWhen: Unbounded[Instant]) =
    eventDatum match {
      case eventData: EventData =>
        eventData.serializableEvent.when <= cutoffWhen
      case _ => true
    }

  def eventIdsAndTheirDatumsObservable(cutoffRevision: Revision,
                                       eventIdInclusion: EventIdInclusion)
    : Observable[(EventId, AbstractEventData)] = {
    for {
      eventId <- toScalaObservable(redisApi.smembers(eventIdsKey))
        .asInstanceOf[Observable[EventId]] filter eventIdInclusion
      eventIdAndDataPair <- toScalaObservable(
        redisApi.zrevrangebyscore(eventCorrectionsKeyFrom(eventId),
                                  cutoffRevision - 1,
                                  initialRevision,
                                  0,
                                  1))
        .asInstanceOf[Observable[AbstractEventData]] map (eventId -> _)
    } yield eventIdAndDataPair
  }

  def pertinentEventDatumsObservable(cutoffRevision: Revision,
                                     eventIdsForNewEvents: Iterable[EventId])
    : Observable[(EventId, AbstractEventData)] = {
    val eventIdsToBeExcluded = eventIdsForNewEvents.toSet
    pertinentEventDatumsObservable(
      cutoffRevision,
      PositiveInfinity(),
      eventId => !eventIdsToBeExcluded.contains(eventId))
  }

  def pertinentEventDatumsObservable(
      cutoffRevision: Revision): Observable[(EventId, AbstractEventData)] =
    pertinentEventDatumsObservable(cutoffRevision,
                                   PositiveInfinity(),
                                   _ => true)

  override protected def transactNewRevision(
      asOf: Instant,
      newEventDatumsFor: Revision => Map[EventId, AbstractEventData],
      buildAndValidateEventTimelineForProposedNewRevision: (
          Seq[(EventId, AbstractEventData)],
          Seq[(EventId, AbstractEventData)]) => Unit): Revision = {
    try {
      val revisionObservable = for {
        _                         <- toScalaObservable(redisApi.watch(asOfsKey))
        nextRevisionPriorToUpdate <- nextRevisionObservable
        newEventDatums: Map[EventId, AbstractEventData] = newEventDatumsFor(
          nextRevisionPriorToUpdate)
        (pertinentEventDatumsExcludingTheNewRevision: Seq[(EventId,
         AbstractEventData)],
         _) <- pertinentEventDatumsObservable(
          nextRevisionPriorToUpdate,
          newEventDatums.keys.toSeq).toList zip
          (for (revisionAsOfs <- revisionAsOfsObservable)
            yield checkRevisionPrecondition(asOf, revisionAsOfs))
        _ = buildAndValidateEventTimelineForProposedNewRevision(
          newEventDatums.toSeq,
          pertinentEventDatumsExcludingTheNewRevision)
        transactionGuid = UUID.randomUUID()
        foo <- Observable
          .from(newEventDatums map {
            case (eventId, eventDatum) =>
              val eventCorrectionsKey          = eventCorrectionsKeyFrom(eventId)
              val timeToExpireGarbageInSeconds = 5
              redisApi.zadd(s"${eventCorrectionsKey}:${transactionGuid}",
                            nextRevisionPriorToUpdate.toDouble,
                            eventDatum) zip
                redisApi
                  .sadd(s"${eventIdsKey}:${transactionGuid}", eventId) zip
                redisApi.expire(s"${eventCorrectionsKey}:${transactionGuid}",
                                timeToExpireGarbageInSeconds) zip
                redisApi.expire(s"${eventIdsKey}:${transactionGuid}",
                                timeToExpireGarbageInSeconds)
          })
          .flatten
          .toList
        transactionStart = toScalaObservable(redisApi.multi())
        transactionBody = Observable
          .from(newEventDatums map {
            case (eventId, eventDatum) =>
              val eventCorrectionsKey = eventCorrectionsKeyFrom(eventId)
              redisApi.zunionstore(
                eventCorrectionsKey,
                eventCorrectionsKey,
                s"${eventCorrectionsKey}:${transactionGuid}") zip
                redisApi.sunionstore(eventIdsKey,
                                     eventIdsKey,
                                     s"${eventIdsKey}:${transactionGuid}")
          })
          .flatten
          .toList zip redisApi.rpush(asOfsKey, asOf)
        transactionEnd = toScalaObservable(redisApi.exec())
        // NASTY HACK: the order of evaluation of the subterms in the next double-zip is vital to ensure that Redis sees
        // the 'multi', body commands and 'exec' verbs in the correct order, even though the processing of the results is
        // handled by ReactiveX, which simply sees three streams of replies.
        _ <- transactionStart zip transactionBody zip transactionEnd
      } yield nextRevisionPriorToUpdate

      revisionObservable.toBlocking.first
    } catch {
      case exception: EncoderException =>
        recoverRedisApi
        throw exception.getCause

      case _: NoSuchElementException =>
        throw new RuntimeException(
          "Concurrent revision attempt detected in revision.")
    }
  }

  private def recoverRedisApi: Observable[String] = {
    teardownRedisApi()
    setupRedisApi()
    redisApi.unwatch()
    redisApi.discard()
  }
}
