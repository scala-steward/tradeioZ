package tradex.domain
package repository

import java.time.{ LocalDate, LocalDateTime }
import zio._
import zio.prelude._
import zio.blocking.Blocking
import zio.interop.catz._

import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import doobie.postgres.implicits._

import config._
import codecs._
import model.account._
import model.order._
import model.instrument._

final class DoobieOrderRepository(xa: Transactor[Task]) {
  import DoobieOrderRepository._

  implicit val OrderAssociative: Associative[Order] =
    new Associative[Order] {
      def combine(left: => Order, right: => Order): Order =
        Order(left.no, left.date, left.accountNo, left.items ++ right.items)
    }

  val orderRepository: OrderRepository.Service = new OrderRepository.Service {
    def queryByOrderNo(no: OrderNo): Task[Option[Order]] = {
      SQL
        .getByOrderNo(no.value.value)
        .to[List]
        .map(_.groupBy(_.no))
        .map {
          _.map { case (_, lis) =>
            lis.reduce((o1, o2) => Associative[Order].combine(o1, o2))
          }.headOption
        }
        .transact(xa)
        .orDie
    }

    def queryByOrderDate(date: LocalDate): Task[List[Order]] = {
      SQL
        .getByOrderDate(date)
        .to[List]
        .map(_.groupBy(_.no))
        .map {
          _.map { case (_, lis) =>
            lis.reduce((o1, o2) => Associative[Order].combine(o1, o2))
          }.toList
        }
        .transact(xa)
        .orDie
    }

    def store(ord: Order): Task[Order] = {
      val res = for {
        _ <- SQL.deleteLineItems(ord.no.value.value).run
        _ <- SQL.upsertOrder(ord).run
        l <- SQL.insertLineItems(ord.items.toList)
      } yield l
      res
        .transact(xa)
        .map(_ => ord)
        .orDie
    }

    def store(orders: NonEmptyList[Order]): Task[Unit] =
      orders.forEach(order => store(order)).map(_ => ())
  }
}

object DoobieOrderRepository {
  def layer: ZLayer[DbConfigProvider with Blocking, Throwable, OrderRepository] = {
    import CatzInterop._

    ZLayer.fromManaged {
      for {
        cfg        <- ZIO.access[DbConfigProvider](_.get).toManaged_
        transactor <- mkTransactor(cfg)
      } yield new DoobieOrderRepository(transactor).orderRepository
    }
  }

  object SQL {
    def upsertOrder(order: Order): Update0 = {
      sql"""
        INSERT INTO orders
        VALUES (${order.no.value.value}, ${order.date}, ${order.accountNo.value.value})
        ON CONFLICT(no) DO UPDATE SET
          dateOfOrder = EXCLUDED.dateOfOrder,
          accountNo   = EXCLUDED.accountNo
       """.update
    }

    def insertLineItems(lis: List[LineItem]): ConnectionIO[Int] = {
      val sql = """
        INSERT INTO lineItems
          (
            orderNo,
            isinCode, 
            quantity, 
            unitPrice,
            buySellFlag
          )
        VALUES ( ?, ?, ?, ?, ? )
       """
      Update[LineItem](sql).updateMany(lis)
    }

    implicit val orderWrite: Write[Order] =
      Write[
        (
            OrderNo,
            AccountNo,
            LocalDateTime
        )
      ].contramap(order =>
        (
          order.no,
          order.accountNo,
          order.date
        )
      )

    implicit def lineItemWrite: Write[LineItem] =
      Write[
        (
            OrderNo,
            ISINCode,
            Quantity,
            UnitPrice,
            BuySell
        )
      ].contramap(lineItem =>
        (
          lineItem.orderNo,
          lineItem.instrument,
          lineItem.quantity,
          lineItem.unitPrice,
          lineItem.buySell
        )
      )

    implicit val orderLineItemRead: Read[Order] =
      Read[
        (
            String,
            LocalDateTime,
            String,
            String,
            BigDecimal,
            BigDecimal,
            String
        )
      ].map { case (no, dt, ano, isin, qty, up, bs) =>
        val vorder = for {
          lineItems <- Order.makeLineItem(no, isin, qty, up, bs)
          order     <- Order.makeOrder(no, dt, ano, NonEmptyList(lineItems))
        } yield order
        vorder
          .fold(exs => throw new Exception(exs.toList.mkString("/")), identity)
      }

    def getByOrderNo(orderNo: String): Query0[Order] =
      sql"""
        SELECT o.no, o.dateOfOrder, o.accountNo, l.isinCode, l.quantity, l.unitPrice, l.buySellFlag
        FROM orders o, lineItems l
        WHERE o.no = $orderNo
        AND   o.no = l.orderNo
       """.query[Order]

    def getByOrderDate(orderDate: LocalDate): Query0[Order] =
      sql"""
        SELECT o.no, o.dateOfOrder, o.accountNo, l.isinCode, l.quantity, l.unitPrice, l.buySellFlag
        FROM orders o, lineItems l
        WHERE Date(o.dateOfOrder) = $orderDate
        AND   o.no = l.orderNo
       """.query[Order]

    def deleteLineItems(orderNo: String): Update0 =
      sql""" 
        DELETE FROM lineItems l WHERE l.orderNo = $orderNo
      """.update
  }
}
