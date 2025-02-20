package io.kagera.persistence

import io.kagera.api._
import io.kagera.api.colored.ExceptionStrategy.{ BlockTransition, Fatal, RetryWithDelay }
import io.kagera.api.colored._
import io.kagera.execution.EventSourcing._
import io.kagera.execution.{ EventSourcing, Instance }
import io.kagera.persistence.Serialization._
import io.kagera.persistence.messages.FailureStrategy
import io.kagera.persistence.messages.FailureStrategy.StrategyType
import io.kagera.persistence.messages.FailureStrategy.StrategyType.{ BLOCK_ALL, BLOCK_TRANSITION }

import scala.runtime.BoxedUnit

object Serialization {

  /**
   * TODO:
   *
   * This approach is fragile, the identifier function cannot change ever or recovery breaks a more robust alternative
   * is to generate the ids and persist them
   */
  def tokenIdentifier[C](p: Place[C]): Any => Int = obj => hashCodeOf[Any](obj)

  def hashCodeOf[T](e: T): Int = {
    if (e == null)
      -1
    else
      e.hashCode()
  }
}

/**
 * This class is responsible for translating the EventSourcing.Event to and from the persistence.messages.Event
 *
 * (which is generated by scalaPB and serializes to protobuff.
 *
 * TODO: allow an ObjectSerializer per Place / Transition ?
 */
class Serialization(serializer: ObjectSerializer) {

  /**
   * De-serializes a persistence.messages.Event to a EvenSourcing.Event. An Instance is required to 'wire' or
   * 'reference' the message back into context.
   */
  def deserializeEvent[S](event: AnyRef): Instance[S] => EventSourcing.Event = event match {
    case e: messages.Initialized => deserializeInitialized(e)
    case e: messages.TransitionFired => deserializeTransitionFired(e)
    case e: messages.TransitionFailed => deserializeTransitionFailed(e)
  }

  /**
   * Serializes an EventSourcing.Event to a persistence.messages.Event.
   */
  def serializeEvent[S](e: EventSourcing.Event): Instance[S] => AnyRef =
    instance =>
      e match {
        case e: InitializedEvent => serializeInitialized(e)
        case e: TransitionFiredEvent => serializeTransitionFired(e)
        case e: TransitionFailedEvent => serializeTransitionFailed(e)
      }

  private def missingFieldException(field: String) = throw new IllegalStateException(
    s"Missing field in serialized data: $field"
  )

  def serializeObject(obj: Any): Option[messages.SerializedData] = {
    (obj != null && !obj.isInstanceOf[Unit]).option {
      serializer.serializeObject(obj.asInstanceOf[AnyRef])
    }
  }

  private def deserializeProducedMarking[S](instance: Instance[S], produced: Seq[messages.ProducedToken]): Marking = {
    produced.foldLeft(Marking.empty) {
      case (accumulated, messages.ProducedToken(Some(placeId), Some(tokenId), Some(count), data, _)) =>
        val place = instance.process.places.getById(placeId)
        val value = data.map(serializer.deserializeObject).getOrElse(BoxedUnit.UNIT)
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ => throw new IllegalStateException("Missing data in persisted ProducedToken")
    }
  }

  private def serializeProducedMarking(produced: Marking): Seq[messages.ProducedToken] = {
    produced.data.toSeq.flatMap { case (place, tokens) =>
      tokens.toSeq.map { case (value, count) =>
        messages.ProducedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count),
          tokenData = serializeObject(value)
        )
      }
    }
  }

  private def serializeConsumedMarking(m: Marking): Seq[messages.ConsumedToken] =
    m.data.toSeq.flatMap { case (place, tokens) =>
      tokens.toSeq.map { case (value, count) =>
        messages.ConsumedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count)
        )
      }
    }

  private def deserializeConsumedMarking[S](instance: Instance[S], e: messages.TransitionFired): Marking = {
    e.consumed.foldLeft(Marking.empty) {
      case (accumulated, messages.ConsumedToken(Some(placeId), Some(tokenId), Some(count), _)) =>
        val place = instance.marking.keySet.getById(placeId)
        val value = instance.marking(place).keySet.find(e => tokenIdentifier(place)(e) == tokenId).get
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ => throw new IllegalStateException("Missing data in persisted ConsumedToken")
    }
  }

  private def deserializeInitialized[S](e: messages.Initialized)(instance: Instance[S]): InitializedEvent = {
    val initialMarking = deserializeProducedMarking(instance, e.initialMarking)
    val initialState = e.initialState.map(serializer.deserializeObject).getOrElse(BoxedUnit.UNIT)
    InitializedEvent(initialMarking, initialState)
  }

  private def serializeInitialized[S](e: InitializedEvent): messages.Initialized = {
    val initialMarking = serializeProducedMarking(e.marking)
    val initialState = serializeObject(e.state)
    messages.Initialized(initialMarking, initialState)
  }

  private def deserializeTransitionFailed[S](e: messages.TransitionFailed): Instance[S] => TransitionFailedEvent = {
    instance =>
      val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
      val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
      val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
      val timeFailed = e.timeFailed.getOrElse(missingFieldException("time_failed"))
      val input = e.inputData.map(serializer.deserializeObject)
      val failureReason = e.failureReason.getOrElse("")
      val failureStrategy = e.failureStrategy.getOrElse(missingFieldException("time_failed")) match {
        case FailureStrategy(Some(StrategyType.BLOCK_TRANSITION), _, _) => BlockTransition
        case FailureStrategy(Some(StrategyType.BLOCK_ALL), _, _) => Fatal
        case FailureStrategy(Some(StrategyType.RETRY), Some(delay), _) => RetryWithDelay(delay)
        case other @ _ => throw new IllegalStateException(s"Invalid failure strategy: $other")
      }

      TransitionFailedEvent(
        jobId,
        transitionId,
        timeStarted,
        timeFailed,
        Marking.empty,
        None,
        failureReason,
        failureStrategy
      )
  }

  private def serializeTransitionFailed(e: TransitionFailedEvent): messages.TransitionFailed = {

    val strategy = e.exceptionStrategy match {
      case BlockTransition => FailureStrategy(Some(StrategyType.BLOCK_TRANSITION))
      case Fatal => FailureStrategy(Some(StrategyType.BLOCK_ALL))
      case RetryWithDelay(delay) => FailureStrategy(Some(StrategyType.RETRY), Some(delay))
    }

    messages.TransitionFailed(
      jobId = Some(e.jobId),
      transitionId = Some(e.transitionId),
      timeStarted = Some(e.timeStarted),
      timeFailed = Some(e.timeFailed),
      inputData = e.input.flatMap(serializeObject _),
      failureReason = Some(e.failureReason),
      failureStrategy = Some(strategy)
    )
  }

  private def serializeTransitionFired(e: TransitionFiredEvent): messages.TransitionFired = {

    val consumedTokens = serializeConsumedMarking(e.consumed)
    val producedTokens = serializeProducedMarking(e.produced)

    messages.TransitionFired(
      jobId = Some(e.jobId),
      transitionId = Some(e.transitionId),
      timeStarted = Some(e.timeStarted),
      timeCompleted = Some(e.timeCompleted),
      consumed = consumedTokens,
      produced = producedTokens,
      data = e.output.flatMap(serializeObject _)
    )
  }

  private def deserializeTransitionFired[S](e: messages.TransitionFired): Instance[S] => TransitionFiredEvent =
    instance => {

      val consumed: Marking = deserializeConsumedMarking(instance, e)
      val produced: Marking = deserializeProducedMarking(instance, e.produced)

      val data = e.data.map(serializer.deserializeObject)

      val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
      val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
      val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
      val timeCompleted = e.timeCompleted.getOrElse(missingFieldException("time_completed"))

      TransitionFiredEvent(jobId, transitionId, timeStarted, timeCompleted, consumed, produced, data)
    }
}
