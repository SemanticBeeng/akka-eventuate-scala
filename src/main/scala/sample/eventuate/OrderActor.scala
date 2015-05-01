/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sample.eventuate

import akka.actor._
import com.rbmhtechnology.eventuate.VersionedAggregate._
import com.rbmhtechnology.eventuate._

import scala.util._

/**
 * An event-sourced actor that manager a single order aggregate, identified by `orderId`.
 */
class OrderActor(orderId: String, val replicaId: String, val eventLog: ActorRef) extends EventsourcedActor with OrderLogging {
  import OrderActor._

  override val aggregateId = Some(orderId)
  private var order = VersionedAggregate(orderId, commandValidation, eventProjection)(OrderDomainCmd, OrderDomainEvt)

  override val onCommand: Receive = {
    case c: CreateOrder =>
      processValidationResult(c.orderId, order.validateCreate(c))
    case c: OrderCommand =>
      processValidationResult(c.orderId, order.validateUpdate(c))
    case c: Resolve =>
      processValidationResult(c.id, order.validateResolve(c.selected, replicaId))
    case GetState =>
      val reply = order.aggregate match {
        case Some(aggregate) => GetStateSuccess(Map(orderId -> aggregate.all))
        case None            => GetStateSuccess(Map.empty)
      }
      sender() ! reply
  }

  override val onEvent: Receive = {
    case e: OrderCreated =>
      order = order.handleCreated(e, lastVectorTimestamp, lastSequenceNr)
      info(s"${e.getClass.getSimpleName}: ${printOrder(order.versions)}")
    case e: OrderEvent =>
      order = order.handleUpdated(e, lastVectorTimestamp, lastSequenceNr)
      info(s"${e.getClass.getSimpleName}: ${printOrder(order.versions)}")
    case e: Resolved =>
      order = order.handleResolved(e, lastVectorTimestamp, lastSequenceNr)
      info(s"Resolved Conflict, keep: ${printOrder(order.versions)}")
  }

  override def recovered(): Unit =
    if(order.versions.nonEmpty) info(s"Initialized from Log: ${printOrder(order.versions)}")

  private def processValidationResult(orderId: String, result: Try[Any]): Unit = result match {
    case Failure(err) =>
      sender() ! CommandFailure(orderId, err)
    case Success(evt) => persist(evt) {
      case Success(e) =>
        onEvent(e)
        sender() ! CommandSuccess(orderId)
      case Failure(e) =>
        sender() ! CommandFailure(orderId, e)
    }
  }

  private def commandValidation: (Order, OrderCommand) => Try[OrderEvent] = {
    case (_, c: CreateOrder) => Success(c.event.copy(creator = replicaId))
    case (_, c: OrderCommand) => Success(c.event)
  }

  private def eventProjection: (Order, OrderEvent) => Order = {
    case (_    , OrderCreated(`orderId`, _)) => Order(orderId)
    case (order, OrderCancelled(`orderId`)) => order.cancel
    case (order, OrderItemAdded(`orderId`, item)) => order.addItem(item)
    case (order, OrderItemRemoved(`orderId`, item)) => order.removeItem(item)
  }
}

object OrderActor {
  trait OrderCommand {
    def orderId: String
    def event: OrderEvent
  }

  trait OrderEvent {
    def orderId: String
  }

  // Order update commands
  case class CreateOrder(orderId: String) extends OrderCommand { val event = OrderCreated(orderId) }
  case class CancelOrder(orderId: String) extends OrderCommand { val event = OrderCancelled(orderId) }
  case class AddOrderItem(orderId: String, item: String) extends OrderCommand  { val event = OrderItemAdded(orderId, item) }
  case class RemoveOrderItem(orderId: String, item: String) extends OrderCommand { val event = OrderItemRemoved(orderId, item) }

  // Order events
  case class OrderCreated(orderId: String, creator: String = "") extends OrderEvent
  case class OrderItemAdded(orderId: String, item: String) extends OrderEvent
  case class OrderItemRemoved(orderId: String, item: String) extends OrderEvent
  case class OrderCancelled(orderId: String) extends OrderEvent

  // Order read commands + replies
  case object GetState
  case class GetStateSuccess(state: Map[String, Seq[Versioned[Order]]])
  case class GetStateFailure(cause: Throwable)

  // General replies
  case class CommandSuccess(orderId: String)
  case class CommandFailure(orderId: String, cause: Throwable)

  implicit object OrderDomainCmd extends DomainCmd[OrderCommand] {
    override def origin(cmd: OrderCommand): String = ""
  }

  implicit object OrderDomainEvt extends DomainEvt[OrderEvent] {
    override def origin(evt: OrderEvent): String = evt match {
      case OrderCreated(_, creator) => creator
      case _ => ""
    }
  }
}
