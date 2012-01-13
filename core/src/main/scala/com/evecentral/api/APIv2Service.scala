package com.evecentral.api

import cc.spray.http.MediaTypes._
import akka.actor.{Actor}
import Actor.actorOf

import scala.xml._

import com.evecentral.dataaccess._
import cc.spray.{RequestContext, Directives}
import cc.spray.typeconversion.DefaultMarshallers

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import com.evecentral.ParameterHelper._

import com.evecentral.{OrderStatistics, ECActorPool}
import com.evecentral.frontend.Formatter.priceString

trait BaseOrderQuery {


  def ordersActor = {
    val r = (Actor.registry.actorsFor[GetOrdersActor]);
    r(0)
  }



}

class QuickLookQuery extends ECActorPool with BaseOrderQuery {
  
  import com.evecentral.ParameterHelper._


  def instance = actorOf(new Actor with DefaultMarshallers  {
    def receive = {
      case ctx: RequestContext =>

        val params = listFromContext(ctx)

        val typeid = singleParam("typeid", params) match {
          case Some(x) => x
          case None => 34
        }
        val setHours = singleParam("sethours", params) match {
          case Some(x) => x
          case None => 24
        }
        val regionLimit = paramsFromQuery("regionlimit", params).map(_.toLong).distinct
        val usesystem = singleParam("usesystem", params)
        val minq = singleParam("setminQ", params)
        ctx.complete(queryQuicklook(typeid, setHours, regionLimit, usesystem, minq))
    }
  })


  def regionName(regions: List[Long]): NodeSeq = {
    regions.foldLeft(Seq[Node]()) {
      (i, regionid) =>
        i ++ <region>{StaticProvider.regionsMap(regionid).name}</region>
    }
  }

  val dateOnly = DateTimeFormat.forPattern("yyyy-MM-dd")

  val dateTime = DateTimeFormat.forPattern("MM-dd hh:mm:ss")

  def showOrders(orders: Option[Seq[MarketOrder]]): NodeSeq = {
    
    orders match {
      case None => Seq[Node]()
      case Some(o) => o.foldLeft(Seq[Node]()) {
        (i, order) =>
          i ++ <order id={order.orderId.toString}>
            <region>{order.region.regionid}</region>
            <station>{order.station.stationid}</station>
            <station_name>{order.station.name}</station_name>
            <security>{order.system.security}</security>
            <range>{order.range}</range>
            <price>{priceString(order.price)}</price>
            <vol_remain>{order.volremain}</vol_remain>
            <min_volume>{order.minVolume}</min_volume>
            <expires>{dateOnly.print(new DateTime().plus(order.expires))}</expires>
            <reported_time>{dateTime.print(order.reportedAt)}</reported_time>
          </order>
      }
    }
  }

  def queryQuicklook(typeid: Long, setHours: Long, regionLimit: List[Long],
                     usesystem: Option[Long], qminq: Option[Long]): NodeSeq = {

    val minq = qminq match {
      case Some(x) => x
      case None => QueryDefaults.minQ(typeid)
    }

    val buyq = GetOrdersFor(Some(true), List(typeid), regionLimit, usesystem match {
      case None => Nil
      case Some(x) => List[Long](x)
    }, setHours)
    val selq = GetOrdersFor(Some(false), List(typeid), regionLimit, usesystem match {
      case None => Nil
      case Some(x) => List[Long](x)
    }, setHours)

    val buyr = ordersActor ? buyq
    val selr = ordersActor ? selq

    <evec_api version="2.0" method="quicklook">
      <quicklook>
        <item>{typeid}</item>
        <itemname>{StaticProvider.typesMap(typeid)}</itemname>
        <regions>{regionName(regionLimit)}</regions>
        <hours>{setHours}</hours>
        <minqty>{minq}</minqty>
        <sell_orders>{showOrders(selr.as[Seq[MarketOrder]])}</sell_orders>
        <buy_orders>{showOrders(buyr.as[Seq[MarketOrder]])}</buy_orders>
      </quicklook>
    </evec_api>
  }

}

case class MarketstatQuery(ctx: RequestContext)
case class EvemonQuery(ctx: RequestContext)

class MarketStatActor extends ECActorPool with BaseOrderQuery {

  def instance = actorOf(new Actor with DefaultMarshallers  {
    def receive = {
      case EvemonQuery(ctx) =>
        val types = List(34, 35, 36, 37, 38, 39, 40, 11399).map(StaticProvider.typesMap(_))

        ctx.complete(<minerals>
          {types.map(evemonMineral(_))}
          </minerals>)
      case MarketstatQuery(ctx) =>

        val params = listFromContext(ctx)

        val typeid = paramsFromQuery("typeid", params).map(_.toLong).distinct

        val setHours = singleParam("hours", params) match {
          case Some(x) => x
          case None => 24
        }
        val regionLimit = paramsFromQuery("regionlimit", params).map(_.toLong).distinct
        val usesystem = singleParam("usesystem", params)
        val minq = singleParam("minQ", params)


        ctx.complete(marketStatQuery(typeid, setHours, regionLimit, usesystem, minq))
    }
  })

  def evemonMineral(mineral: MarketType) : NodeSeq = {
    val buyq = GetOrdersFor(None, List(mineral.typeid), StaticProvider.empireRegions.map(_.regionid), Nil)
    val r = (ordersActor ? buyq).as[Seq[MarketOrder]] getOrElse List[MarketOrder]()
    val s = OrderStatistics(r)
    <mineral>
      <name>{mineral.name}</name>
      <price>{priceString(s.wavg)}</price>
    </mineral>
  }

  def subGroupXml(alls: OrderStatistics) : NodeSeq = {
    <volume>{alls.volume}</volume>
      <avg>{priceString(alls.wavg)}</avg>
      <max>{priceString(alls.max)}</max>
      <min>{priceString(alls.min)}</min>
      <stddev>{priceString(alls.stdDev)}</stddev>
      <median>{priceString(alls.median)}</median>
      <percentile>{priceString(alls.fivePercent)}</percentile>
  }

  def fetchOrdersFor(typeid: Long, setHours: Long, regionLimit: Seq[Long],
                     usesystem: Option[Long], minq: Option[Long]) : (Seq[MarketOrder], Seq[MarketOrder]) = {

    val numminq = minq match {
      case Some(q) => q
      case None => QueryDefaults.minQ(typeid)
    }

    val buyq = GetOrdersFor(Some(true), List(typeid), regionLimit, usesystem match {
      case None => Nil
      case Some(x) => List[Long](x)
    }, setHours, numminq)

    val selq = GetOrdersFor(Some(false), List(typeid), regionLimit, usesystem match {
      case None => Nil
      case Some(x) => List[Long](x)
    }, setHours, numminq)

    val self = (ordersActor ? selq)
    val buyf = (ordersActor ? buyq)


    /**
     * !TODO: This could be nicer - allow partial XML generation without waiting on the orders actor
     */
    val selr = self.as[Seq[MarketOrder]] getOrElse List[MarketOrder]()
    val buyr = buyf.as[Seq[MarketOrder]] getOrElse List[MarketOrder]()
    (selr, buyr)
  }
  
  def typeXml(typeid: Long, setHours: Long, regionLimit: Seq[Long], usesystem: Option[Long], minq: Option[Long]) : NodeSeq = {

    val (buyr, selr) = fetchOrdersFor(typeid, setHours, regionLimit, usesystem, minq)

    val allr = selr ++ buyr // Warning: Linear append
    val alls = OrderStatistics(allr)
    val sels = OrderStatistics(selr)
    val buys = OrderStatistics(buyr)
    <type id={typeid.toString}>
      <buy>
        {subGroupXml(buys)}
      </buy>
      <sell>
        {subGroupXml(sels)}
      </sell>
      <all>
        {subGroupXml(alls)}
      </all>
    </type>
  }
  
  def marketStatQuery(types: Seq[Long], hours: Long, regionLimit: Seq[Long], usesystem: Option[Long],  minq: Option[Long]) : NodeSeq = {
    <evec_api version="2.0" method="marketstat_xml">
      <marketstat>
        {types.map(t => typeXml(t, hours, regionLimit, usesystem, minq))}
      </marketstat>
    </evec_api>
  }

}

trait APIv2Service extends Directives {

  val quicklookActor = actorOf(new QuickLookQuery())

  val marketstatActor = actorOf(new MarketStatActor())

  val v2Service = {
    path("api/quicklook") {
      (get | post) {
            ctx =>
              (quicklookActor ! ctx)

        }
    } ~ path("api/marketstat") { // Todo: this feels too repetitive, fix it
      (get | post) {
        ctx =>
          (marketstatActor ! MarketstatQuery(ctx))
      }
    } ~ path("api/evemon") {
      (get | post) {
        ctx =>
          (marketstatActor ! EvemonQuery(ctx))
    } ~ path("api/goofy") {
        get {
          respondWithContentType(`text/html`) {
            completeWith {
              <html>
                <body>
                  <form method="POST" action="/api/quicklook">
                      <input type="text" name="typeid" value="2003"/>
                      <input type="text" name="regionlimit" value="10000049"/>
                      <input type="submit" value="Go"/>
                  </form>
                </body>
              </html>
            }
          }
        }
      }
    }
  }

}

