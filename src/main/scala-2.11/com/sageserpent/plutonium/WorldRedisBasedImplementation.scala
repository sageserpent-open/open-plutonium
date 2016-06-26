package com.sageserpent.plutonium

import java.time.Instant
import java.util.UUID

import akka.util.ByteString
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.serializers.JavaSerializer
import com.esotericsoftware.kryo.{Kryo, Serializer}
import com.sageserpent.americium.{PositiveInfinity, Unbounded}
import com.twitter.chill.{KryoInstantiator, KryoPool}
import org.objenesis.strategy.SerializingInstantiatorStrategy
import redis.api.Limit
import redis.{ByteStringFormatter, RedisClient}

import scala.Ordering.Implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionException, Future}
import scala.reflect.runtime.universe._


/**
  * Created by Gerard on 27/05/2016.
  */

object WorldRedisBasedImplementation {
  import WorldImplementationCodeFactoring._

  val redisNamespaceComponentSeparator = ":"

  val javaSerializer = new JavaSerializer

  class ItemReconstitutionDataSerializer[Raw <: Identified] extends Serializer[Recorder#ItemReconstitutionData[Raw]]{
    override def write(kryo: Kryo, output: Output, data: Recorder#ItemReconstitutionData[Raw]): Unit = {
      val (id, typeTag) = data
      kryo.writeClassAndObject(output, id)
      kryo.writeObject(output, typeTag, javaSerializer)
    }

    override def read(kryo: Kryo, input: Input, dataType: Class[Recorder#ItemReconstitutionData[Raw]]): Recorder#ItemReconstitutionData[Raw] = {
      val id = kryo.readClassAndObject(input).asInstanceOf[Raw#Id]
      val typeTag = kryo.readObject[TypeTag[Raw]](input, classOf[TypeTag[Raw]], javaSerializer)
      id -> typeTag
    }
  }

  val kryoPool = KryoPool.withByteArrayOutputStream(40, new KryoInstantiator().withRegistrar((kryo: Kryo) => {
    def registerSerializerForItemReconstitutionData[Raw <: Identified]() = {
      kryo.register(classOf[Recorder#ItemReconstitutionData[Raw]], new ItemReconstitutionDataSerializer[Raw])
    }
    registerSerializerForItemReconstitutionData()
    kryo.setInstantiatorStrategy(new SerializingInstantiatorStrategy)
  }))

  def byteStringFormatterFor[Value: TypeTag] = new ByteStringFormatter[Value] {
    override def serialize(data: Value): ByteString = ByteString(kryoPool.toBytesWithClass(data))

    override def deserialize(bs: ByteString): Value = kryoPool.fromBytes(bs.toArray).asInstanceOf[Value]
  }

  implicit val byteStringFormatterForInstant = byteStringFormatterFor[Instant]

  implicit val byteStringFormatterForEventData = byteStringFormatterFor[AbstractEventData]
}

class WorldRedisBasedImplementation[EventId: TypeTag](redisClient: RedisClient, identityGuid: String) extends WorldImplementationCodeFactoring[EventId] {
  parentWorld =>
  import World._
  import WorldImplementationCodeFactoring._
  import WorldRedisBasedImplementation._

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val byteStringFormatterForEventId = byteStringFormatterFor[EventId]

  val asOfsKey = s"${identityGuid}${redisNamespaceComponentSeparator}asOfs"

  val eventCorrectionsKeyPrefix = s"${identityGuid}${redisNamespaceComponentSeparator}eventCorrectionsFor${redisNamespaceComponentSeparator}"

  val eventIdsKey = s"${identityGuid}${redisNamespaceComponentSeparator}eventIds"

  def eventCorrectionsKeyFrom(eventId: EventId) = s"${eventCorrectionsKeyPrefix}${eventId}"

  override def nextRevision: Revision = Await.result(nextRevisionFuture, Duration.Inf)

  protected def nextRevisionFuture: Future[EventOrderingTiebreakerIndex] = {
    redisClient.llen(asOfsKey) map (_.toInt)
  }

  override def forkExperimentalWorld(scope: javaApi.Scope): World[EventId] = new WorldRedisBasedImplementation[EventId](redisClient = redisClient, identityGuid = s"${parentWorld.identityGuid}-experimental-${UUID.randomUUID()}"){
    val baseWorld = parentWorld
    val numberOfRevisionsInCommon = scope.nextRevision
    val cutoffWhenAfterWhichHistoriesDiverge = scope.when

    override protected def nextRevisionFuture: Future[EventOrderingTiebreakerIndex] = super.nextRevisionFuture map (numberOfRevisionsInCommon + _)

    override protected def revisionAsOfsFuture: Future[Seq[Instant]] = for {
      revisionAsOfsFromBaseWorld <- baseWorld.revisionAsOfsFuture
      revisionAsOfsFromSuper <- super.revisionAsOfsFuture
    } yield (revisionAsOfsFromBaseWorld take numberOfRevisionsInCommon) ++ revisionAsOfsFromSuper

    override protected def pertinentEventDatumsFuture(cutoffRevision: Revision, cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion): Future[Seq[AbstractEventData]] = {
      val cutoffWhenForBaseWorld = cutoffWhen min cutoffWhenAfterWhichHistoriesDiverge
      if (cutoffRevision > numberOfRevisionsInCommon) for {
        (eventIds, eventDatums) <- eventIdsAndTheirDatums(cutoffRevision, cutoffWhen, eventIdInclusion)
        eventIdsToBeExcluded = eventIds.toSet
        eventDatumsFromBaseWorld <- baseWorld.pertinentEventDatumsFuture(numberOfRevisionsInCommon, cutoffWhenForBaseWorld, eventId => !eventIdsToBeExcluded.contains(eventId) && eventIdInclusion(eventId))
      } yield eventDatums ++ eventDatumsFromBaseWorld
      else baseWorld.pertinentEventDatumsFuture(cutoffRevision, cutoffWhenForBaseWorld, eventIdInclusion)
    }
  }

  override def revisionAsOfs: Seq[Instant] = Await.result(revisionAsOfsFuture, Duration.Inf)

  protected def revisionAsOfsFuture: Future[Seq[Instant]] = {
    redisClient.lrange[Instant](asOfsKey, 0, -1)
  }

  override protected def eventTimeline(cutoffRevision: Revision): Seq[SerializableEvent] = eventTimelineFrom(Await.result(pertinentEventDatumsFuture(cutoffRevision), Duration.Inf))

  type EventIdInclusion = EventId => Boolean

  protected def pertinentEventDatumsFuture(cutoffRevision: Revision, cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion): Future[Seq[AbstractEventData]] =
    eventIdsAndTheirDatums(cutoffRevision, cutoffWhen, eventIdInclusion) map (_._2)

  def eventIdsAndTheirDatums(cutoffRevision: Revision,  cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion): Future[(Seq[EventId], Seq[AbstractEventData])] = {
    for {
      eventIds: Seq[EventId] <- redisClient.smembers[EventId](eventIdsKey) map (_ filter eventIdInclusion)
      eventDatums <- Future.traverse(eventIds)(eventId =>
        redisClient.zrevrangebyscore[AbstractEventData](eventCorrectionsKeyFrom(eventId),
          min = Limit(initialRevision, inclusive = true),
          max = Limit(cutoffRevision, inclusive = false),
          limit = Some(0, 1))) map (_.flatten) filter {
        case eventData: EventData => eventData.serializableEvent.when <= cutoffWhen
        case _ => true
      }
    } yield eventIds -> eventDatums
  }

  def pertinentEventDatumsFuture(cutoffRevision: Revision, eventIds: Iterable[EventId]): Future[Seq[AbstractEventData]] = {
    val eventIdsToBeIncluded = eventIds.toSet
    pertinentEventDatumsFuture(cutoffRevision, PositiveInfinity(), eventIdsToBeIncluded.contains)
  }

  def pertinentEventDatumsFuture(cutoffRevision: Revision): Future[Seq[AbstractEventData]] =
    pertinentEventDatumsFuture(cutoffRevision, PositiveInfinity(), _ => true)

  override protected def transactNewRevision(asOf: Instant,
                                             newEventDatumsFor: Revision => Map[EventId, AbstractEventData],
                                             buildAndValidateEventTimelineForProposedNewRevision: (Map[EventId, AbstractEventData], Revision, Seq[AbstractEventData], Set[AbstractEventData]) => Unit): Revision = {
    // TODO - concurrency safety!

    try {
      Await.result(for {
        nextRevisionPriorToUpdate <- nextRevisionFuture
        newEventDatums: Map[EventId, AbstractEventData] = newEventDatumsFor(nextRevisionPriorToUpdate)
        revisionAsOfs <- revisionAsOfsFuture
        _ = checkRevisionPrecondition(asOf, revisionAsOfs)
        obsoleteEventDatums: Set[AbstractEventData] <- pertinentEventDatumsFuture(nextRevisionPriorToUpdate, newEventDatums.keys.toSeq) map (Set(_: _*))
        pertinentEventDatumsExcludingTheNewRevision: Seq[AbstractEventData] <- pertinentEventDatumsFuture(nextRevisionPriorToUpdate)
        _ = buildAndValidateEventTimelineForProposedNewRevision(newEventDatums, nextRevisionPriorToUpdate, pertinentEventDatumsExcludingTheNewRevision, obsoleteEventDatums)
        _ <- Future.traverse(newEventDatums) {
          case (eventId, eventDatum) => for {
            _ <- redisClient.zadd[AbstractEventData](eventCorrectionsKeyFrom(eventId), nextRevisionPriorToUpdate.toDouble -> eventDatum)
            _ <- redisClient.sadd[EventId](eventIdsKey, eventId)
          } yield ()
        }
        _ <- redisClient.rpush[Instant](asOfsKey, asOf)
      } yield nextRevisionPriorToUpdate, Duration.Inf)
    } catch {
      case exception: ExecutionException => throw exception.getCause
    }
  }
}
