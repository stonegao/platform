/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package muspelheim

import com.precog.yggdrasil._

trait MiscStackSpecs extends EvalStackSpecs {
  implicit def add_~=(d: Double) = new AlmostEqual(d)
  implicit val precision = Precision(0.000000001)

  "the full stack" should {
    "ensure that with operation uses inner-join semantics" in {
      val input = """
        | clicks := //clicks
        | a := {dummy: if clicks.time < 1329326691939 then 1 else 0}
        | clicks with {a:a}
        | """.stripMargin
        
      forall(evalE(input)) {
        case (ids, SObject(fields)) => fields must haveKey("a")
      }
    }

    "reduce sets" in {
      val input = """
        | medals := //summer_games/london_medals
        |   sum(medals.HeightIncm) + mean(medals.Weight) - count(medals.Age) + stdDev(medals.S)
      """.stripMargin

      val result = evalE(input) 

      result must haveSize(1)

      val actual = result collect {
        case (ids, SDecimal(num)) if ids.length == 0 => num.toDouble ~= 174257.3421888046
      }

      actual must contain(true).only
    }

    "recognize the datetime parse function" in {
      val input = """
        | std::time::parse("2011-02-21 01:09:59", "yyyy-MM-dd HH:mm:ss")
      """.stripMargin

      val result = evalE(input)

      val actual = result collect {
        case (ids, SString(time)) if ids.length == 0 => time
      }

      actual mustEqual Set("2011-02-21T01:09:59.000Z")
    }

    "timelib functions should accept ISO8601 with a space instead of a T" in {
      val input = """
        | std::time::year("2011-02-21 01:09:59")
      """.stripMargin

      val result = evalE(input)

      val actual = result collect {
        case (ids, SDecimal(year)) if ids.length == 0 => year
      }

      actual mustEqual Set(2011)
    }

    "return the left size of a true if/else operation" in {
      val input1 = """
        | if true then //clicks else //campaigns
      """.stripMargin

      val result1 = evalE(input1)

      val input2 = """
        | //clicks
      """.stripMargin

      val result2 = evalE(input2)

      result1 mustEqual result2
    }

    "return the right size of a false if/else operation" in {
      val input1 = """
        | if false then //clicks else //campaigns
      """.stripMargin

      val result1 = evalE(input1)

      val input2 = """
        | //campaigns
      """.stripMargin

      val result2 = evalE(input2)

      result1 mustEqual result2
    }

    "accept division inside an object" in {
      val input = """
        | data := //conversions
        | 
        | x := solve 'productID
        |   data' := data where data.product.ID = 'productID
        |   { count: count(data' where data'.customer.isCasualGamer = false),
        |     sum: sum(data'.marketing.uniqueVisitors  where data'.customer.isCasualGamer = false) }
        | 
        | { max: max(x.sum/x.count), min: min(x.sum/x.count) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)

          obj.keys mustEqual(Set("min", "max"))
          (obj("min") match { case SDecimal(num) => num.toDouble ~= 862.7464285714286 }) mustEqual true
          (obj("max") match { case SDecimal(num) => num.toDouble ~= 941.0645161290323 }) mustEqual true
      }
    }

    "accept division of two BigDecimals" in {
      val input = "92233720368547758073 / 12223372036854775807"

      val result = evalE(input)

      result must haveSize(1)

      forall(result) {
        case (ids, SDecimal(num)) =>
          ids must haveSize(0)
          (num.toDouble ~= 7.54568543692) mustEqual true
      }
    }

    "call the same function multiple times with different input" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | stats(variable) := {max: max(variable), min: min(variable), sum: sum(variable)}
        | 
        | weightStats := stats(medals.Weight)
        | heightStats := stats(medals.HeightIncm)
        | 
        | [weightStats, heightStats]
      """.stripMargin

      val result = evalE(input)

      result must haveSize(1)

      forall(result) {
        case (ids, SArray(Vector(SObject(map1), SObject(map2)))) =>
          ids must haveSize(0)

          map1.keySet mustEqual(Set("min", "max", "sum"))
          map1("min") mustEqual SDecimal(39)
          map1("max") mustEqual SDecimal(165)
          map1("sum") mustEqual SDecimal(67509)

          map2.keySet mustEqual(Set("min", "max", "sum"))
          map2("min") mustEqual SDecimal(140)
          map2("max") mustEqual SDecimal(208)
          map2("sum") mustEqual SDecimal(175202)
      }
    }

    "perform various reductions on transspecable sets" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | { sum: sum(std::math::floor(std::math::cbrt(medals.HeightIncm))),
        |   max: max(medals.HeightIncm),
        |   min: min(medals.Weight),
        |   stdDev: stdDev(std::math::sqrt(medals.Weight)),
        |   count: count(medals.Weight = 39),
        |   minmax: min(max(medals.HeightIncm))
        | }
      """.stripMargin

      val result = evalE(input)

      result must haveSize(1)

      forall(result) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          
          obj must haveKey("sum")
          obj must haveKey("max")
          obj must haveKey("min")
          obj must haveKey("stdDev")
          obj must haveKey("count")
          obj must haveKey("minmax")

          (obj("sum") match { case SDecimal(num) => num.toDouble ~= 4965 }) mustEqual true
          (obj("max") match { case SDecimal(num) => num.toDouble ~= 208 }) mustEqual true
          (obj("min") match { case SDecimal(num) => num.toDouble ~= 39 }) mustEqual true
          (obj("stdDev") match { case SDecimal(num) => num.toDouble ~= 0.9076874907113496 }) mustEqual true
          (obj("count") match { case SDecimal(num) => num.toDouble ~= 1019 }) mustEqual true
          (obj("minmax") match { case SDecimal(num) => num.toDouble ~= 208 }) mustEqual true
      }
    }

    "solve on a union with a `with` clause" in {
      val input = """
        | medals := //summer_games/london_medals
        | athletes := //summer_games/athletes
        | 
        | data := athletes union (medals with { winner: medals."Medal winner" })
        | 
        | solve 'winner 
        |   { winner: 'winner, num: count(data.winner where data.winner = 'winner) } 
      """.stripMargin

      val result = evalE(input)

      result must haveSize(2)

      val results2 = result collect {
        case (ids, obj) if ids.length == 1 => obj
      }

      results2 mustEqual(Set(SObject(Map("num" -> SDecimal(1018), "winner" -> SString("YES"))), SObject(Map("num" -> SDecimal(1), "winner" -> SString("YEs")))))
    }

    "solve with a generic where inside a function" in {
      val input = """
        | medals := //summer_games/london_medals
        | athletes := //summer_games/athletes
        | 
        | data := athletes union (medals with { winner: medals."Medal winner" })
        | 
        | f(x, y) := x where y
        | 
        | solve 'winner 
        |   { winner: 'winner, num: count(f(data.winner, data.winner = 'winner)) } 
      """.stripMargin

      val result = evalE(input)

      result must haveSize(2)

      val results2 = result collect {
        case (ids, obj) if ids.length == 1 => obj
      }

      results2 mustEqual(Set(SObject(Map("num" -> SDecimal(1018), "winner" -> SString("YES"))), SObject(Map("num" -> SDecimal(1), "winner" -> SString("YEs")))))
    }
    
    "solve the results of a set and a stdlib op1 function" in {
      val input = """
        | clicks := //clicks
        | clicks' := clicks with { foo: std::time::getMillis("2012-10-29") }
        | solve 'a clicks' where clicks'.time = 'a
        | """.stripMargin
        
      val result = evalE(input)
      result must not(beEmpty)        // TODO
    }
    
    "solve involving extras with a stdlib op1 function" in {
      val input = """
        | import std::time::*
        | 
        | agents := //clicks
        | data := { agentId: agents.userId, millis: getMillis(agents.timeString) }
        | 
        | upperBound := getMillis("2012-04-03T23:59:59")
        | 
        | solve 'agent
        |   data where data.millis < upperBound & data.agentId = 'agent
        | """.stripMargin
      
      val results = evalE(input)
      results must not(beEmpty)     // TODO
    }

    "perform a simple join by value sorting" in {
      val input = """
        | clicks := //clicks
        | views := //views
        |
        | clicks ~ views
        |   std::string::concat(clicks.pageId, views.pageId) where clicks.userId = views.userId
        """.stripMargin

      val resultsE = evalE(input)

      resultsE must haveSize(473)

      val results = resultsE collect {
        case (ids, str) if ids.length == 2 => str
      }

      results must contain(SString("page-2page-2"))
      results must contain(SString("page-2page-1"))
      results must contain(SString("page-4page-3"))
      results must contain(SString("page-4page-4"))
      results must contain(SString("page-3page-4"))
      results must contain(SString("page-3page-0"))
      results must contain(SString("page-0page-2"))
      results must contain(SString("page-0page-4"))
      results must contain(SString("page-0page-0"))
      results must contain(SString("page-0page-1"))
      results must contain(SString("page-4page-2"))
      results must contain(SString("page-0page-3"))
      results must contain(SString("page-1page-1"))
      results must contain(SString("page-1page-4"))
      results must contain(SString("page-1page-0"))
      results must contain(SString("page-1page-2"))
      results must contain(SString("page-1page-3"))
      results must contain(SString("page-3page-3"))
      results must contain(SString("page-3page-1"))
      results must contain(SString("page-4page-0"))
      results must contain(SString("page-4page-1"))
      results must contain(SString("page-3page-2"))
      results must contain(SString("page-2page-3"))
      results must contain(SString("page-2page-4"))
      results must contain(SString("page-2page-0")) 
    }

    "union sets coming out of a solve" >> {
      val input = """
        clicks := //clicks
        foobar := solve 'a {userId: 'a, size: count(clicks where clicks.userId = 'a)}
        foobaz := solve 'b {pageId: 'b, size: count(clicks where clicks.pageId = 'b)}
        foobar union foobaz
      """.stripMargin

      val results = evalE(input)

      results must haveSize(26)

      forall(results) {
        case (ids, SObject(obj)) => {
          ids must haveSize(1)
          obj must haveSize(2)
          obj must haveKey("userId") or haveKey("pageId")
          obj must haveKey("size")
        }
      }

      val containsUserId = results collect {
        case (_, SObject(obj)) if obj contains "userId" => obj
      }

      containsUserId must haveSize(21)
      containsUserId collect {
        case obj => obj("userId")
      } mustEqual Set(
        SString("user-1000"), SString("user-1001"), SString("user-1002"), SString("user-1003"), SString("user-1004"), SString("user-1005"),
        SString("user-1006"), SString("user-1007"), SString("user-1008"), SString("user-1009"), SString("user-1010"), SString("user-1011"),
        SString("user-1012"), SString("user-1013"), SString("user-1014"), SString("user-1015"), SString("user-1016"), SString("user-1017"),
        SString("user-1018"), SString("user-1019"), SString("user-1020"))

      val containsPageId = results collect {
        case (_, SObject(obj)) if obj contains "pageId" => obj
      }

      containsPageId must haveSize(5)
      containsPageId collect {
        case obj => obj("pageId")
      } mustEqual Set(SString("page-0"), SString("page-1"), SString("page-2"), SString("page-3"), SString("page-4"))
    }

    "accept a solve involving a tic-var as an actual" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | age(x) := medals.Age = x
        |
        | x := solve 'age
        |   medals' := medals where age('age)
        |   sum(medals'.Weight where medals'.Sex = "F")
        | 
        | { min: min(x), max: max(x) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          obj mustEqual(Map("min" -> SDecimal(50), "max" -> SDecimal(2768)))
      }
    }

    "accept a solve involving a formal in a where clause" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | f(y) := solve 'age
        |   medals' := medals where y = 'age
        |   sum(medals'.Weight where medals'.Sex = "F")
        | 
        | { min: min(f(medals.Age)), max: max(f(medals.Age)) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          obj mustEqual(Map("min" -> SDecimal(50), "max" -> SDecimal(2768)))
      }
    }

    // Regression test for #39652091
    "call union on two dispatches of the same function" in {
      val input = """
        | medals := //summer_games/london_medals
        |
        | f(x) :=
        |   medals' := medals where medals.Country = x
        |   medals'' := new medals'
        |
        |   medals'' ~ medals'
        |     {a: medals'.Country, b: medals''.Country} where medals'.Total = medals''.Total
        |
        | f("India") union f("Canada")
      """.stripMargin

      val results = evalE(input)

      results must haveSize(16 + 570)

      val maps = results.toSeq collect {
        case (ids, SObject(obj)) => obj
      }

      val india = maps filter { _.values forall { _ == SString("India") } }
      india.size mustEqual(16)

      val canada = maps filter { _.values forall { _ == SString("Canada") } }
      canada.size mustEqual(570)
    }

    "accept a solve involving formals of formals" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | f(y) := 
        |   g(x) := 
        |     solve 'age
        |       medals' := medals where x = 'age
        |       sum(medals'.Weight where medals'.Sex = "F")
        |   g(y.Age)
        | 
        | { min: min(f(medals)), max: max(f(medals)) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          obj mustEqual(Map("min" -> SDecimal(50), "max" -> SDecimal(2768)))
      }
    }

    "correctly assign reductions to the correct field in an object" in {
      val input = """
        | medals := //summer_games/london_medals
        |
        | x := solve 'age
        |   medals' := medals where medals.Age = 'age
        |   sum(medals'.Weight where medals'.Sex = "F")
        | 
        | { min: min(x), max: max(x) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          obj mustEqual(Map("min" -> SDecimal(50), "max" -> SDecimal(2768)))
      }
    }

    "correctly assign reductions to the correct field in an object with three reductions each on the same set" in {
      val input = """
        | medals := //summer_games/london_medals
        |
        | x := solve 'age
        |   medals' := medals where medals.Age = 'age
        |   sum(medals'.Weight where medals'.Sex = "F")
        | 
        | { min: min(x), max: max(x), stdDev: stdDev(x) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          
          obj.keys mustEqual Set("min", "max", "stdDev")
  
          (obj("min") match { case SDecimal(num) => num.toDouble ~= 50 }) mustEqual true
          (obj("max") match { case SDecimal(num) => num.toDouble ~= 2768 }) mustEqual true
          (obj("stdDev") match { case SDecimal(num) => num.toDouble ~= 917.6314704474534 }) mustEqual true
      }
    }

    "correctly assign reductions to the correct field in an object with three reductions each on the same set" in {
      val input = """
        | medals := //summer_games/london_medals
        |
        | x := solve 'age
        |   medals' := medals where medals.Age = 'age
        |   sum(medals'.Weight where medals'.Sex = "F")
        | 
        | { min: min(x), max: max(x), stdDev: stdDev(x) }
      """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)
          
          obj.keys mustEqual Set("min", "max", "stdDev")
  
          (obj("min") match { case SDecimal(num) => num.toDouble ~= 50 }) mustEqual true
          (obj("max") match { case SDecimal(num) => num.toDouble ~= 2768 }) mustEqual true
          (obj("stdDev") match { case SDecimal(num) => num.toDouble ~= 917.6314704474534 }) mustEqual true
      }
    }

    "accept a solve involving a where as an actual" >> {
      val input = """
        | clicks := //clicks
        | f(x) := x
        | counts := solve 'time
        |   {count: count(f(clicks where clicks.time = 'time)) }

        | cov := std::stats::cov(counts.count, counts.count)
        | counts with {covariance: cov}
        | """.stripMargin

      val results = evalE(input)

      results must haveSize(81)  

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(1)
          obj must haveKey("covariance")
          obj must haveKey("count")
      }
    }

    "accept a solve involving relation as an actual" >> {
      val input = """
        | clicks := //clicks
        | f(x) := x
        | counts := solve 'time
        |   { count: count(clicks where f(clicks.time = 'time)) }

        | cov := std::stats::cov(counts.count, counts.count)
        | counts with {covariance: cov}
        | """.stripMargin

      val results = evalE(input)

      results must haveSize(81)  

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(1)
          obj must haveKey("covariance")
          obj must haveKey("count")
      }
    }

    "accept covariance inside an object with'd with another object" >> {
      val input = """
        clicks := //clicks
        counts := solve 'time
          { count: count(clicks where clicks.time = 'time) }

        cov := std::stats::cov(counts.count, counts.count)
        counts with {covariance: cov}
      """.stripMargin

      val results = evalE(input)

      results must haveSize(81)  

      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(1)
          obj must haveKey("covariance")
          obj must haveKey("count")
      }
    }
    
    "have the correct number of identities and values in a relate" >> {
      "with the sum plus the LHS" >> {
        val input = """
          | //clicks ~ //campaigns
          | sum := (//clicks).time + (//campaigns).cpm
          | sum + (//clicks).time""".stripMargin

        val results = evalE(input)

        results must haveSize(10000)

        forall(results) {
          case (ids, _) => ids must haveSize(2)
        }
      }
      
      "with the sum plus the RHS" >> {
        val input = """
          | //clicks ~ //campaigns
          | sum := (//clicks).time + (//campaigns).cpm
          | sum + (//campaigns).cpm""".stripMargin

        val results = evalE(input)

        results must haveSize(10000)

        forall(results) {
          case (ids, _) => ids must haveSize(2)
        }
      }
    }

    "union two wheres of the same dynamic provenance" >> {
      val input = """
      | clicks := //clicks
      | clicks' := new clicks
      |
      | xs := clicks where clicks.time > 0
      | ys := clicks' where clicks'.pageId != "blah"
      |
      | xs union ys""".stripMargin

      val results = evalE(input)

      results must haveSize(200)
    }

    "use the where operator on a unioned set" >> {
      "campaigns.gender" >> {
        val input = """
          | a := //campaigns union //clicks
          |   a where a.gender = "female" """.stripMargin
          
        val results = evalE(input)
        
        results must haveSize(46)
        
        forall(results) {
          case (ids, SObject(obj)) => {
            ids.length must_== 1
            obj must haveSize(5)
            obj must contain("gender" -> SString("female"))
          }
          case r => failure("Result has wrong shape: "+r)
        }
      }

      "clicks.platform" >> {
        val input = """
          | a := //campaigns union //clicks
          |   a where a.platform = "android" """.stripMargin
          
        val results = evalE(input)
        
        results must haveSize(72)
        
        forall(results) {
          case (ids, SObject(obj)) => {
            ids.length must_== 1
            obj must haveSize(5)
            obj must contain("platform" -> SString("android"))
          }
          case r => failure("Result has wrong shape: "+r)
        }
      }
    }

    "basic set difference queries" >> {
      "clicks difference clicks" >> {
        val input = "//clicks difference //clicks"
        val results = evalE(input)

        results must haveSize(0)
      }
      "clicks.timeString difference clicks.timeString" >> {
        val input = "(//clicks).timeString difference (//clicks).timeString"
        val results = evalE(input)

        results must haveSize(0)
      }      
    }

    "basic intersect and union queries" >> {
      "constant intersection" >> {
        val input = "4 intersect 4"
        val results = evalE(input)

        results must haveSize(1)
        
        forall(results) {
          case (ids, SDecimal(d)) => 
            ids.length must_== 0
            d mustEqual 4 
          case r => failure("Result has wrong shape: "+r)
        }
      }
      "constant union" >> {
        val input = "4 union 5"
        val results = evalE(input)

        results must haveSize(2)
        
        forall(results) {
          case (ids, SDecimal(d)) => 
            ids.length must_== 0
            Set(4,5) must contain(d) 
          case r => failure("Result has wrong shape: "+r)
        }
      }
      "empty intersection" >> {
        val input = "4 intersect 5"
        val results = evalE(input)

        results must beEmpty
      }
      "heterogeneous union" >> {
        val input = "{foo: 3} union 9"
        val results = evalE(input)

        results must haveSize(2)
        
        forall(results) {
          case (ids, SDecimal(d)) => 
            ids.length must_== 0
            d mustEqual 9
          case (ids, SObject(obj)) => 
            ids.length must_== 0
            obj must contain("foo" -> SDecimal(3)) 
          case r => failure("Result has wrong shape: "+r)
        }
      }
      "heterogeneous intersection" >> {
        val input = "obj := {foo: 5} obj.foo intersect 5"
        val results = evalE(input)

        results must haveSize(1)
        
        forall(results) {
          case (ids, SDecimal(d)) => 
            ids.length must_== 0
            d mustEqual 5 
          case r => failure("Result has wrong shape: "+r)
        }
      }
      "intersection of differently sized arrays" >> {
        val input = "arr := [1,2,3] arr[0] intersect 1"
        val results = evalE(input)

        results must haveSize(1)
        
        forall(results) {
          case (ids, SDecimal(d)) => 
            ids.length must_== 0
            d mustEqual 1 
          case r => failure("Result has wrong shape: "+r)
        }
      }
      "heterogeneous union doing strange things with identities" >> {
        val input = "{foo: (//clicks).pageId, bar: (//clicks).userId} union //views"
        val results = evalE(input)

        results must haveSize(200)
      }
      "union with operation against same coproduct" >> {
        val input = "(//clicks union //views).time + (//clicks union //views).time"
        val results = evalE(input)

        results must haveSize(200)
      }
      "union with operation on left part of coproduct" >> {
        val input = "(//clicks union //views).time + (//clicks).time"
        val results = evalE(input)

        results must haveSize(100)
      }
      "union with operation on right part of coproduct" >> {
        val input = "(//clicks union //views).time + (//views).time"
        val results = evalE(input)

        results must haveSize(100)
      }
    }

    "intersect a union" >> {
      "campaigns.gender" >> {
        val input = """
          | campaign := (//campaigns).campaign
          | cpm := (//campaigns).cpm
          | a := campaign union cpm
          |   a intersect campaign """.stripMargin
          
        val results = evalE(input)
        
        results must haveSize(100)
        
        forall(results) {
          case (ids, SString(campaign)) =>
            ids.length must_== 1
            Set("c16","c9","c21","c15","c26","c5","c18","c7","c4","c17","c11","c13","c12","c28","c23","c14","c10","c19","c6","c24","c22","c20") must contain(campaign)
          case r => failure("Result has wrong shape: "+r)
        }
      }

      "union the same set when two different variables are assigned to it" >> {
          val input = """
            | a := //clicks
            | b := //clicks
            | a union b""".stripMargin

          val results = evalE(input)

          results must haveSize(100)
      }

      "clicks.platform" >> {
        val input = """
          | campaign := (//campaigns).campaign
          | cpm := (//campaigns).cpm
          | a := campaign union cpm
          |   a intersect cpm """.stripMargin
          
        val results = evalE(input)
        
        results must haveSize(100)
        
        forall(results) {
          case (ids, SDecimal(num)) =>
            ids.length must_== 1
            Set(100,39,91,77,96,99,48,67,10,17,90,58,20,38,1,43,49,23,72,42,94,16,9,21,52,5,40,62,4,33,28,54,70,82,76,22,6,12,65,31,80,45,51,89,69) must contain(num)
          case r => failure("Result has wrong shape: "+r)
        }
      }
    }

    "union with an object" >> {
      val input = """
        campaigns := //campaigns
        clicks := //clicks
        obj := {foo: campaigns.cpm, bar: campaigns.campaign}
        obj union clicks""".stripMargin

      val results = evalE(input)

      results must haveSize(200)
    }

    "use the where operator on a key with string values" in {
      val input = """//campaigns where (//campaigns).platform = "android" """
      val results = evalE(input)
      
      results must haveSize(72)

      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(5)
          obj must contain("platform" -> SString("android"))
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "use the where operator on a key with numeric values" in {
      val input = "//campaigns where (//campaigns).cpm = 1 "
      val results = evalE(input)
      
      results must haveSize(34)

      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(5)
          obj must contain("cpm" -> SDecimal(1))
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "use the where operator on a key with array values" in {
      val input = "//campaigns where (//campaigns).ageRange = [37, 48]"
      val results = evalE(input)
      
      results must haveSize(39)

      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(5)
          obj must contain("ageRange" -> SArray(Vector(SDecimal(37), SDecimal(48))))
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "evaluate the with operator across the campaigns dataset" in {
      val input = "count(//campaigns with { t: 42 })"
      eval(input) mustEqual Set(SDecimal(100))
    }

    "perform distinct" >> {
      "on a homogenous set of numbers" >> {
        val input = """
          | a := //campaigns
          |   distinct(a.gender)""".stripMargin

        eval(input) mustEqual Set(SString("female"), SString("male"))   
      }

      "on set of strings formed by a union" >> {
        val input = """
          | gender := (//campaigns).gender
          | pageId := (//clicks).pageId
          | distinct(gender union pageId)""".stripMargin

        eval(input) mustEqual Set(SString("female"), SString("male"), SString("page-0"), SString("page-1"), SString("page-2"), SString("page-3"), SString("page-4"))   
      }
    }

    "map object creation over the campaigns dataset" in {
      val input = "{ aa: (//campaigns).campaign }"
      val results = evalE(input)
      
      results must haveSize(100)
      
      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(1)
          obj must haveKey("aa")
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }
    
    "perform a naive cartesian product on the campaigns dataset" in {
      val input = """
        | a := //campaigns
        | b := new a
        |
        | a ~ b
        |   { aa: a.campaign, bb: b.campaign }""".stripMargin
        
      val results = evalE(input)
      
      results must haveSize(10000)
      
      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 2
          obj must haveSize(2)
          obj must haveKey("aa")
          obj must haveKey("bb")
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "correctly handle cross-match situations" in {
      val input = """
        | campaigns := //campaigns
        | clicks := //clicks
        | 
        | campaigns ~ clicks
        |   campaigns = campaigns
        |     & clicks = clicks
        |     & clicks = clicks""".stripMargin
        
      val results = evalE(input)
      
      results must haveSize(100 * 100)
      
      forall(results) {
        case (ids, SBoolean(b)) => {
          ids must haveSize(2)
          b mustEqual true
        }
        case r => failure("Result has wrong shape: " + r)
      }
    }

    "add sets of different types" >> {
      "a set of numbers and a set of strings" >> {
        val input = "(//campaigns).cpm + (//campaigns).gender"

        eval(input) mustEqual Set()
      }

      "a set of numbers and a set of arrays" >> {
        val input = "(//campaigns).cpm + (//campaigns).ageRange"

        eval(input) mustEqual Set()
      }

      "a set of arrays and a set of strings" >> {
        val input = "(//campaigns).gender + (//campaigns).ageRange"

        eval(input) mustEqual Set()
      }
    }

    "return all possible value results from an underconstrained solve" in {
      val input = """
        | campaigns := //campaigns
        | solve 'a 
        |   campaigns.gender where campaigns.platform = 'a""".stripMargin
        
      val results = evalE(input)
      
      results must haveSize(100)
      
      forall(results) {
        case (ids, SString(gender)) =>
        ids.length must_== 1
          gender must beOneOf("male", "female")
        case r => failure("Result has wrong shape: "+r)
      }
    }
    
    "determine a histogram of genders on campaigns" in {
      val input = """
        | campaigns := //campaigns
        | solve 'gender 
        |   { gender: 'gender, num: count(campaigns.gender where campaigns.gender = 'gender) }""".stripMargin
        
      eval(input) mustEqual Set(
        SObject(Map("gender" -> SString("female"), "num" -> SDecimal(46))),
        SObject(Map("gender" -> SString("male"), "num" -> SDecimal(54))))
    }
    
    "determine a histogram of STATE on (tweets union tweets)" in {
      val input = """
        | tweets := //election/tweets 
        | 
        | data := tweets union tweets
        | 
        | solve 'state 
        |   data' := data where data.STATE = 'state 
        |   {
        |     state: 'state, 
        |     count: count(data')
        |   }
        | """.stripMargin
        
      val resultsE = evalE(input)
      
      resultsE must haveSize(52)
      
      val results = resultsE collect {
        case (ids, sv) if ids.length == 1 => sv
      }
      
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("319")), "state" -> SString("01"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("267")), "state" -> SString("02"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("248")), "state" -> SString("04"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("229")), "state" -> SString("05"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("242")), "state" -> SString("06"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("265")), "state" -> SString("08"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("207")), "state" -> SString("09"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("265")), "state" -> SString("10"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("183")), "state" -> SString("11"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("275")), "state" -> SString("12"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("267")), "state" -> SString("13"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("240")), "state" -> SString("15"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("269")), "state" -> SString("16"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("268")), "state" -> SString("17"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("221")), "state" -> SString("18"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("238")), "state" -> SString("19"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("251")), "state" -> SString("20"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("220")), "state" -> SString("21"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("215")), "state" -> SString("22"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("245")), "state" -> SString("23"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("232")), "state" -> SString("24"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("234")), "state" -> SString("25"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("239")), "state" -> SString("26"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("242")), "state" -> SString("27"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("219")), "state" -> SString("28"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("213")), "state" -> SString("29"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("216")), "state" -> SString("30"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("195")), "state" -> SString("31"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("196")), "state" -> SString("32"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("223")), "state" -> SString("33"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("207")), "state" -> SString("34"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("221")), "state" -> SString("35"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("204")), "state" -> SString("36"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("221")), "state" -> SString("37"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("231")), "state" -> SString("38"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("167")), "state" -> SString("39"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("219")), "state" -> SString("40"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("200")), "state" -> SString("41"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("206")), "state" -> SString("42"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("230")), "state" -> SString("44"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("224")), "state" -> SString("45"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("184")), "state" -> SString("46"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("215")), "state" -> SString("47"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("189")), "state" -> SString("48"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("227")), "state" -> SString("49"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("233")), "state" -> SString("50"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("206")), "state" -> SString("51"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("232")), "state" -> SString("53"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("223")), "state" -> SString("54"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("193")), "state" -> SString("55"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("186")), "state" -> SString("56"))))
      results must contain(SObject(Map("count" -> SDecimal(BigDecimal("153")), "state" -> SString("72"))))
    }
    
    "evaluate nathan's query, once and for all" in {
      val input = """
        | import std::time::*
        | 
        | lastHour := //election/tweets 
        | thisHour:= //election/tweets2 
        | 
        | lastHour' := lastHour where minuteOfHour(lastHour.timeStamp) > 36
        | data := thisHour union lastHour'
        | 
        | combined := solve 'stateName, 'state 
        |   data' := data where data.stateName = 'stateName & data.STATE = 'state 
        |   {stateName: 'stateName,
        |    state: 'state, 
        |    obamaSentimentScore: sum(data'.score where data'.candidate = "Obama") 
        |                         / count(data' where data'.candidate = "Obama"), 
        |    romneySentimentScore: sum(data'.score where data'.candidate = "Romney") 
        |                         / count(data' where data'.candidate = "Romney")} 
        | 
        | {stateName: combined.stateName, state: combined.state, sentiment: (50 * (combined.obamaSentimentScore - combined.romneySentimentScore)) + 50}
        | """.stripMargin
      
      evalE(input) must not(beEmpty)
    }

    "load a nonexistent dataset with a dot in the name" in {
      val input = """
        | (//foo).bar""".stripMargin
     
      eval(input) mustEqual Set()
    }

    "deref an array with a where" in {
      val input = """
        | a := [3,4,5]
        | a where a[0] = 1""".stripMargin

      val results = eval(input)

      results must haveSize(0)
    }

    "deref an object with a where" in {
      val input = """
        | a := {foo: 5}
        | a where a.foo = 1""".stripMargin

      val results = eval(input)

      results must haveSize(0)
    }

    "evaluate reductions on filters" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        |   {
        |   sum: sum(medals.Age where medals.Age = 30),
        |   mean: mean(std::math::max(medals.B, medals.Age)),
        |   max: max(medals.G where medals.Sex = "F"),
        |   stdDev: stdDev(std::math::pow(medals.Total, medals.S))
        |   }
        """.stripMargin

      val results = evalE(input)
      
      forall(results) {
        case (ids, SObject(obj)) =>
          ids must haveSize(0)

          obj must haveKey("sum")
          obj must haveKey("mean")
          obj must haveKey("max")
          obj must haveKey("stdDev")

          (obj("sum") match { case SDecimal(num) => num.toDouble ~= 1590 }) mustEqual true
          (obj("mean") match { case SDecimal(num) => num.toDouble ~= 26.371933267909714 }) mustEqual true
          (obj("max") match { case SDecimal(num) => num.toDouble ~= 2.5 }) mustEqual true
          (obj("stdDev") match { case SDecimal(num) => num.toDouble ~= 0.36790736209203007 }) mustEqual true
      }
    }

    "evaluate single reduction on a filter" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | max(medals.G where medals.Sex = "F")
        """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SDecimal(num)) =>
          ids must haveSize(0)
          num mustEqual(2.5)
      }
    }

    "evaluate single reduction on a object deref" in {
      val input = """
        | medals := //summer_games/london_medals
        | 
        | max(medals.G)
        """.stripMargin

      val results = evalE(input)

      results must haveSize(1)

      forall(results) {
        case (ids, SDecimal(num)) =>
          ids must haveSize(0)
          num mustEqual(2.5)
      }
    }

    "evaluate functions from each library" >> {
      "Stringlib" >> {
        val input = """
          | gender := distinct((//campaigns).gender)
          | std::string::concat("alpha ", gender)""".stripMargin

        eval(input) mustEqual Set(SString("alpha female"), SString("alpha male"))
      }

      "Mathlib" >> {
        val input = """
          | cpm := distinct((//campaigns).cpm)
          | selectCpm := cpm where cpm < 10
          | std::math::pow(selectCpm, 2)""".stripMargin

        eval(input) mustEqual Set(SDecimal(25), SDecimal(1), SDecimal(36), SDecimal(81), SDecimal(16))
      }

      "Timelib" >> {
        val input = """
          | time := (//clicks).timeString
          | std::time::yearsBetween(time, "2012-02-09T19:31:13.616+10:00")""".stripMargin

        val results = evalE(input) 
        val results2 = results map {
          case (ids, SDecimal(d)) => 
            ids.length must_== 1
            d.toInt
          case r => failure("Result has wrong shape: "+r)
        }

        results2 must contain(0).only
      }

      "Statslib" >> {  //note: there are no identities because these functions involve reductions
        "Correlation" >> {
          val input = """
            | cpm := (//campaigns).cpm
            | std::stats::corr(cpm, 10)""".stripMargin

          val results = evalE(input) 
          val results2 = results map {
            case (ids, SDecimal(d)) => 
              ids.length must_== 0
              d.toDouble
            case r => failure("Result has wrong shape: "+r)
          }

          results2 must haveSize(0)
        }

        // From bug #38535135
        "Correlation on solve results" >> {
          val input = """
            data := //summer_games/london_medals 
            byCountry := solve 'Country
              data' := data where data.Country = 'Country
              {country: 'Country,
              gold: sum(data'.G ),
              silver: sum(data'.S )}

            std::stats::corr(byCountry.gold,byCountry.silver)
            """

          val results = evalE(input)
          results must haveSize(1)
        }

        "Covariance" >> {
          val input = """
            | cpm := (//campaigns).cpm
            | std::stats::cov(cpm, 10)""".stripMargin

          val results = evalE(input) 
          results must haveSize(1)

          val results2 = results map {
            case (ids, SDecimal(d)) => 
              ids.length must_== 0
              d.toDouble
            case r => failure("Result has wrong shape: "+r)
          }
          results2 must contain(0)
        }

        "Linear Regression" >> {
          val input = """
            | cpm := (//campaigns).cpm
            | std::stats::linReg(cpm, 10)""".stripMargin

          val results = evalE(input) 
          results must haveSize(1)

          val results2 = results map {
            case (ids, SObject(fields)) => 
              ids.length must_== 0
              fields
            case r => failure("Result has wrong shape: "+r)
          }
          results2 must contain(Map("slope" -> SDecimal(0), "intercept" -> SDecimal(10)))
        }
      }
    }
 
    "set critical conditions given an empty set" in {
      val input = """
        | solve 'a
        |   //campaigns where (//campaigns).foo = 'a""".stripMargin

      val results = evalE(input)
      results must beEmpty
    }

    "use NotEq correctly" in {
      val input = """//campaigns where (//campaigns).gender != "female" """.stripMargin

      val results = evalE(input)

      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(5)
          obj must contain("gender" -> SString("male"))
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "evaluate a solve constrained by inclusion" in {
      val input = """
        | clicks := //clicks
        | views := //views
        |
        | solve 'page = views.pageId
        |   count(clicks where clicks.pageId = 'page)
        | """.stripMargin
      
      val results = evalE(input)
      
      results must haveSize(5)
      
      val stripped = results collect {
        case (ids, SDecimal(d)) if ids.length == 1 => d
      }
      
      stripped must contain(12)
      stripped must contain(15)
      stripped must contain(19)
      stripped must contain(27)
    }

    "evaluate sliding window in a" >> {
      "solve expression" >> {
        val input = """
          | campaigns := //campaigns
          | nums := distinct(campaigns.cpm where campaigns.cpm < 10)
          | solve 'n
          |   m := max(nums where nums < 'n)
          |   (nums where nums = 'n) + m""".stripMargin

        eval(input) mustEqual Set(SDecimal(15), SDecimal(11), SDecimal(9), SDecimal(5))
      }
    }

    "evaluate a function of two parameters" in {
      val input = """
        | fun(a, b) := 
        |   //campaigns where (//campaigns).ageRange = a & (//campaigns).gender = b
        | fun([25,36], "female")""".stripMargin

      val results = evalE(input) 
      results must haveSize(14)
      
      forall(results) {
        case (ids, SObject(obj)) => {
          ids.length must_== 1
          obj must haveSize(5)
          obj must contain("ageRange" -> SArray(Vector(SDecimal(25), SDecimal(36))))
          obj must contain("gender" -> SString("female"))
        }
        case r => failure("Result has wrong shape: "+r)
      }
    }

    "evaluate a solve of two parameters" in {
      val input = """
        | campaigns := //campaigns
        | gender := campaigns.gender
        | platform := campaigns.platform
        | solve 'a, 'b
        |   g := gender where gender = 'a
        |   p := platform where platform = 'b
        |   campaigns where g = p""".stripMargin

      eval(input) mustEqual Set()
    }

    "determine a histogram of a composite key of revenue and campaign" in {
      val input = """
        | campaigns := //campaigns
        | organizations := //organizations
        | 
        | solve 'revenue = organizations.revenue & 'campaign = organizations.campaign
        |   campaigns' := campaigns where campaigns.campaign = 'campaign
        |   { revenue: 'revenue, num: count(campaigns') }""".stripMargin

      val resultsE = evalE(input)
      resultsE must haveSize(63)

      val results = resultsE collect {
        case (ids, obj) if ids.length == 1 => obj
      }
      
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("11")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("7")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("8")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("8")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("8")))))
      results must contain(SObject(Map("revenue" -> SString("500M+"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("500M+"), "num" -> SDecimal(BigDecimal("8")))))
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("50-250M"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("1")))))
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("7")))))
      results must contain(SObject(Map("revenue" -> SString("50-250M"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("500M+"), "num" -> SDecimal(BigDecimal("7")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("1")))))
      results must contain(SObject(Map("revenue" -> SString("50-250M"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("2")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("50-250M"), "num" -> SDecimal(BigDecimal("8")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("500M+"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("2")))))
      results must contain(SObject(Map("revenue" -> SString("500M+"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("250-500M"), "num" -> SDecimal(BigDecimal("7")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("4")))))
      results must contain(SObject(Map("revenue" -> SString("5-50M"), "num" -> SDecimal(BigDecimal("5")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("3")))))
      results must contain(SObject(Map("revenue" -> SString("<500K"), "num" -> SDecimal(BigDecimal("1")))))
      results must contain(SObject(Map("revenue" -> SString("500K-5M"), "num" -> SDecimal(BigDecimal("7")))))
    }

    "evaluate a function of multiple counts" in {
      val input = """
        | import std::math::floor
        | clicks := //clicks
        | 
        | solve 'timeZone
        |   page0 := count(clicks.pageId where clicks.pageId = "page-0" & clicks.timeZone = 'timeZone)
        |   page1 := count(clicks.pageId where clicks.pageId = "page-1" & clicks.timeZone = 'timeZone)
        |   
        |   { timeZone: 'timeZone, ratio: floor(100 * (page0 / page1)) }
        """.stripMargin

      val resultsE = evalE(input)
      val results = resultsE.map(_._2)

      results must haveSize(11)
      results must contain(SObject(Map("timeZone" -> SString("+14:00"), "ratio" -> SDecimal(BigDecimal("100.0")))))
      results must contain(SObject(Map("timeZone" -> SString("-02:00"), "ratio" -> SDecimal(BigDecimal("50.0")))))
      results must contain(SObject(Map("timeZone" -> SString("-03:00"), "ratio" -> SDecimal(BigDecimal("100.0")))))
      results must contain(SObject(Map("timeZone" -> SString("+11:00"), "ratio" -> SDecimal(BigDecimal("200.0")))))
      results must contain(SObject(Map("timeZone" -> SString("+12:00"), "ratio" -> SDecimal(BigDecimal("33.0"))))) //TODO: this should be 33.3333 - find out why precision is hosed
      results must contain(SObject(Map("timeZone" -> SString("+04:00"), "ratio" -> SDecimal(BigDecimal("200.0")))))
      results must contain(SObject(Map("timeZone" -> SString("+01:00"), "ratio" -> SDecimal(BigDecimal("25.0")))))
      results must contain(SObject(Map("timeZone" -> SString("-01:00"), "ratio" -> SDecimal(BigDecimal("100.0")))))
      results must contain(SObject(Map("timeZone" -> SString("-06:00"), "ratio" -> SDecimal(BigDecimal("300.0")))))
      results must contain(SObject(Map("timeZone" -> SString("+02:00"), "ratio" -> SDecimal(BigDecimal("100.0")))))
      results must contain(SObject(Map("timeZone" -> SString("-05:00"), "ratio" -> SDecimal(BigDecimal("50.0")))))
    }

    "evaluate reductions inside and outside of solves" in {
      val input = """
        | clicks := //clicks
        |
        | countsForTimezone := solve 'timeZone
        |   clicksForZone := clicks where clicks.timeZone = 'timeZone
        |   {timeZone: 'timeZone, clickCount: count(clicksForZone)}
        |
        | mostClicks := max(countsForTimezone.clickCount)
        |
        | countsForTimezone where countsForTimezone.clickCount = mostClicks
        """.stripMargin

      val resultsE = evalE(input)

      println(resultsE)
    }

    "determine click times around each click" in {
      val input = """
        | clicks := //clicks
        | 
        | solve 'time = clicks.time
        |   belowTime := max(clicks.time where clicks.time < 'time)
        |   aboveTime := min(clicks.time where clicks.time > 'time)
        |   
        |   {
        |     time: 'time,
        |     below: belowTime,
        |     above: aboveTime
        |   }
        """.stripMargin

      val resultsE = evalE(input)
      val results = resultsE.map(_._2)

      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329526464104")), "below" -> SDecimal(BigDecimal("1329470485350")), "time" -> SDecimal(BigDecimal("1329475769211")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329301670072")), "below" -> SDecimal(BigDecimal("1329262444197")), "time" -> SDecimal(BigDecimal("1329275667592")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873610")), "below" -> SDecimal(BigDecimal("1329629900716")), "time" -> SDecimal(BigDecimal("1329643873609")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873610")), "below" -> SDecimal(BigDecimal("1329629900716")), "time" -> SDecimal(BigDecimal("1329643873609")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329076541429")), "below" -> SDecimal(BigDecimal("1329004284627")), "time" -> SDecimal(BigDecimal("1329020233656")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873611")), "below" -> SDecimal(BigDecimal("1329643873609")), "time" -> SDecimal(BigDecimal("1329643873610")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329333416645")), "below" -> SDecimal(BigDecimal("1329324578771")), "time" -> SDecimal(BigDecimal("1329326691939")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873612")), "below" -> SDecimal(BigDecimal("1329643873610")), "time" -> SDecimal(BigDecimal("1329643873611")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873612")), "below" -> SDecimal(BigDecimal("1328779873610")), "time" -> SDecimal(BigDecimal("1328779873611")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873612")), "below" -> SDecimal(BigDecimal("1328779873610")), "time" -> SDecimal(BigDecimal("1328779873611")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328797020396")), "below" -> SDecimal(BigDecimal("1328788056054")), "time" -> SDecimal(BigDecimal("1328791229826")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873613")), "below" -> SDecimal(BigDecimal("1328779873611")), "time" -> SDecimal(BigDecimal("1328779873612")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328809637371")), "below" -> SDecimal(BigDecimal("1328791229826")), "time" -> SDecimal(BigDecimal("1328797020396")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873614")), "below" -> SDecimal(BigDecimal("1329643873611")), "time" -> SDecimal(BigDecimal("1329643873612")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329004284627")), "below" -> SDecimal(BigDecimal("1328984890189")), "time" -> SDecimal(BigDecimal("1328985989055")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329360253555")), "below" -> SDecimal(BigDecimal("1329333416645")), "time" -> SDecimal(BigDecimal("1329345853072")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873614")), "below" -> SDecimal(BigDecimal("1328779873612")), "time" -> SDecimal(BigDecimal("1328779873613")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873614")), "below" -> SDecimal(BigDecimal("1328779873612")), "time" -> SDecimal(BigDecimal("1328779873613")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873614")), "below" -> SDecimal(BigDecimal("1328779873612")), "time" -> SDecimal(BigDecimal("1328779873613")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873618")), "below" -> SDecimal(BigDecimal("1329643873612")), "time" -> SDecimal(BigDecimal("1329643873614")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873618")), "below" -> SDecimal(BigDecimal("1329643873612")), "time" -> SDecimal(BigDecimal("1329643873614")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329629900716")), "below" -> SDecimal(BigDecimal("1329554034828")), "time" -> SDecimal(BigDecimal("1329589296943")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873616")), "below" -> SDecimal(BigDecimal("1328779873613")), "time" -> SDecimal(BigDecimal("1328779873614")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328812534981")), "below" -> SDecimal(BigDecimal("1328797020396")), "time" -> SDecimal(BigDecimal("1328809637371")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329190541217")), "below" -> SDecimal(BigDecimal("1329164110718")), "time" -> SDecimal(BigDecimal("1329165986272")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328984890189")), "below" -> SDecimal(BigDecimal("1328887823569")), "time" -> SDecimal(BigDecimal("1328969812140")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329456302829")), "below" -> SDecimal(BigDecimal("1329441529486")), "time" -> SDecimal(BigDecimal("1329446825698")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873617")), "below" -> SDecimal(BigDecimal("1328779873614")), "time" -> SDecimal(BigDecimal("1328779873616")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873617")), "below" -> SDecimal(BigDecimal("1328779873614")), "time" -> SDecimal(BigDecimal("1328779873616")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328791229826")), "below" -> SDecimal(BigDecimal("1328780398002")), "time" -> SDecimal(BigDecimal("1328788056054")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329475769211")), "below" -> SDecimal(BigDecimal("1329456302829")), "time" -> SDecimal(BigDecimal("1329470485350")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329383567193")), "below" -> SDecimal(BigDecimal("1329369083745")), "time" -> SDecimal(BigDecimal("1329369428834")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873619")), "below" -> SDecimal(BigDecimal("1328779873616")), "time" -> SDecimal(BigDecimal("1328779873617")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873619")), "below" -> SDecimal(BigDecimal("1328779873616")), "time" -> SDecimal(BigDecimal("1328779873617")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329244747076")), "below" -> SDecimal(BigDecimal("1329190541217")), "time" -> SDecimal(BigDecimal("1329211954428")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329554034828")), "below" -> SDecimal(BigDecimal("1329475769211")), "time" -> SDecimal(BigDecimal("1329526464104")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329211954428")), "below" -> SDecimal(BigDecimal("1329165986272")), "time" -> SDecimal(BigDecimal("1329190541217")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873620")), "below" -> SDecimal(BigDecimal("1329643873614")), "time" -> SDecimal(BigDecimal("1329643873618")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329137951622")), "below" -> SDecimal(BigDecimal("1329076541429")), "time" -> SDecimal(BigDecimal("1329094347814")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328887823569")), "below" -> SDecimal(BigDecimal("1328847243682")), "time" -> SDecimal(BigDecimal("1328877415620")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873621")), "below" -> SDecimal(BigDecimal("1328779873617")), "time" -> SDecimal(BigDecimal("1328779873619")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873621")), "below" -> SDecimal(BigDecimal("1328779873617")), "time" -> SDecimal(BigDecimal("1328779873619")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873621")), "below" -> SDecimal(BigDecimal("1328779873617")), "time" -> SDecimal(BigDecimal("1328779873619")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873621")), "below" -> SDecimal(BigDecimal("1328779873617")), "time" -> SDecimal(BigDecimal("1328779873619")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329165986272")), "below" -> SDecimal(BigDecimal("1329159525492")), "time" -> SDecimal(BigDecimal("1329164110718")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329309914296")), "below" -> SDecimal(BigDecimal("1329275667592")), "time" -> SDecimal(BigDecimal("1329301670072")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873621")), "below" -> SDecimal(BigDecimal("1329643873618")), "time" -> SDecimal(BigDecimal("1329643873620")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329345853072")), "below" -> SDecimal(BigDecimal("1329326691939")), "time" -> SDecimal(BigDecimal("1329333416645")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329324578771")), "below" -> SDecimal(BigDecimal("1329309914296")), "time" -> SDecimal(BigDecimal("1329310139168")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329385943949")), "below" -> SDecimal(BigDecimal("1329369428834")), "time" -> SDecimal(BigDecimal("1329383567193")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873622")), "below" -> SDecimal(BigDecimal("1328779873619")), "time" -> SDecimal(BigDecimal("1328779873621")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873622")), "below" -> SDecimal(BigDecimal("1329643873620")), "time" -> SDecimal(BigDecimal("1329643873621")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873622")), "below" -> SDecimal(BigDecimal("1329643873620")), "time" -> SDecimal(BigDecimal("1329643873621")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873623")), "below" -> SDecimal(BigDecimal("1328779873621")), "time" -> SDecimal(BigDecimal("1328779873622")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329369428834")), "below" -> SDecimal(BigDecimal("1329360253555")), "time" -> SDecimal(BigDecimal("1329369083745")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329470485350")), "below" -> SDecimal(BigDecimal("1329446825698")), "time" -> SDecimal(BigDecimal("1329456302829")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873623")), "below" -> SDecimal(BigDecimal("1329643873621")), "time" -> SDecimal(BigDecimal("1329643873622")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329159525492")), "below" -> SDecimal(BigDecimal("1329094347814")), "time" -> SDecimal(BigDecimal("1329137951622")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873624")), "below" -> SDecimal(BigDecimal("1329643873622")), "time" -> SDecimal(BigDecimal("1329643873623")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873624")), "below" -> SDecimal(BigDecimal("1329643873622")), "time" -> SDecimal(BigDecimal("1329643873623")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329408502943")), "below" -> SDecimal(BigDecimal("1329383567193")), "time" -> SDecimal(BigDecimal("1329385943949")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873624")), "below" -> SDecimal(BigDecimal("1328779873622")), "time" -> SDecimal(BigDecimal("1328779873623")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329262444197")), "below" -> SDecimal(BigDecimal("1329244747076")), "time" -> SDecimal(BigDecimal("1329253270269")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873625")), "below" -> SDecimal(BigDecimal("1328779873623")), "time" -> SDecimal(BigDecimal("1328779873624")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873625")), "below" -> SDecimal(BigDecimal("1328779873623")), "time" -> SDecimal(BigDecimal("1328779873624")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873625")), "below" -> SDecimal(BigDecimal("1329643873623")), "time" -> SDecimal(BigDecimal("1329643873624")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329369083745")), "below" -> SDecimal(BigDecimal("1329345853072")), "time" -> SDecimal(BigDecimal("1329360253555")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329164110718")), "below" -> SDecimal(BigDecimal("1329137951622")), "time" -> SDecimal(BigDecimal("1329159525492")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873626")), "below" -> SDecimal(BigDecimal("1328779873624")), "time" -> SDecimal(BigDecimal("1328779873625")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328969812140")), "below" -> SDecimal(BigDecimal("1328877415620")), "time" -> SDecimal(BigDecimal("1328887823569")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329310139168")), "below" -> SDecimal(BigDecimal("1329301670072")), "time" -> SDecimal(BigDecimal("1329309914296")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873627")), "below" -> SDecimal(BigDecimal("1329643873624")), "time" -> SDecimal(BigDecimal("1329643873625")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329094347814")), "below" -> SDecimal(BigDecimal("1329020233656")), "time" -> SDecimal(BigDecimal("1329076541429")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329446825698")), "below" -> SDecimal(BigDecimal("1329408502943")), "time" -> SDecimal(BigDecimal("1329441529486")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873628")), "below" -> SDecimal(BigDecimal("1328779873625")), "time" -> SDecimal(BigDecimal("1328779873626")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328840918817")), "below" -> SDecimal(BigDecimal("1328809637371")), "time" -> SDecimal(BigDecimal("1328812534981")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329275667592")), "below" -> SDecimal(BigDecimal("1329253270269")), "time" -> SDecimal(BigDecimal("1329262444197")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328788056054")), "below" -> SDecimal(BigDecimal("1328779873631")), "time" -> SDecimal(BigDecimal("1328780398002")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873628")), "below" -> SDecimal(BigDecimal("1329643873625")), "time" -> SDecimal(BigDecimal("1329643873627")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873628")), "below" -> SDecimal(BigDecimal("1329643873625")), "time" -> SDecimal(BigDecimal("1329643873627")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328847243682")), "below" -> SDecimal(BigDecimal("1328812534981")), "time" -> SDecimal(BigDecimal("1328840918817")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329253270269")), "below" -> SDecimal(BigDecimal("1329211954428")), "time" -> SDecimal(BigDecimal("1329244747076")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873629")), "below" -> SDecimal(BigDecimal("1328779873626")), "time" -> SDecimal(BigDecimal("1328779873628")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873629")), "below" -> SDecimal(BigDecimal("1328779873626")), "time" -> SDecimal(BigDecimal("1328779873628")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873629")), "below" -> SDecimal(BigDecimal("1328779873626")), "time" -> SDecimal(BigDecimal("1328779873628")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329020233656")), "below" -> SDecimal(BigDecimal("1328985989055")), "time" -> SDecimal(BigDecimal("1329004284627")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328985989055")), "below" -> SDecimal(BigDecimal("1328969812140")), "time" -> SDecimal(BigDecimal("1328984890189")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329589296943")), "below" -> SDecimal(BigDecimal("1329526464104")), "time" -> SDecimal(BigDecimal("1329554034828")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873630")), "below" -> SDecimal(BigDecimal("1328779873628")), "time" -> SDecimal(BigDecimal("1328779873629")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328877415620")), "below" -> SDecimal(BigDecimal("1328840918817")), "time" -> SDecimal(BigDecimal("1328847243682")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329326691939")), "below" -> SDecimal(BigDecimal("1329310139168")), "time" -> SDecimal(BigDecimal("1329324578771")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873631")), "below" -> SDecimal(BigDecimal("1328779873629")), "time" -> SDecimal(BigDecimal("1328779873630")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873631")), "below" -> SDecimal(BigDecimal("1328779873629")), "time" -> SDecimal(BigDecimal("1328779873630")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873631")), "below" -> SDecimal(BigDecimal("1328779873629")), "time" -> SDecimal(BigDecimal("1328779873630")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328779873631")), "below" -> SDecimal(BigDecimal("1328779873629")), "time" -> SDecimal(BigDecimal("1328779873630")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329441529486")), "below" -> SDecimal(BigDecimal("1329385943949")), "time" -> SDecimal(BigDecimal("1329408502943")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1328780398002")), "below" -> SDecimal(BigDecimal("1328779873630")), "time" -> SDecimal(BigDecimal("1328779873631")))))
      results must contain(SObject(Map("above" -> SDecimal(BigDecimal("1329643873609")), "below" -> SDecimal(BigDecimal("1329589296943")), "time" -> SDecimal(BigDecimal("1329629900716")))))
    }
     
  
    "determine most isolated clicks in time" in {
      val input = """
        | clicks := //clicks
        | 
        | spacings := solve 'time
        |   click := clicks where clicks.time = 'time
        |   belowTime := max(clicks.time where clicks.time < 'time)
        |   aboveTime := min(clicks.time where clicks.time > 'time)
        |   
        |   {
        |     click: click,
        |     below: click.time - belowTime,
        |     above: aboveTime - click.time
        |   }
        | 
        | meanAbove := mean(spacings.above)
        | meanBelow := mean(spacings.below)
        | 
        | spacings.click where spacings.below > meanBelow & spacings.above > meanAbove""".stripMargin

        val resultsE = evalE(input)

        resultsE must haveSize(20)
        
        val results = resultsE collect {
          case (ids, sv) if ids.length == 1 => sv
        }
        
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329275667592")), "timeZone" -> SString("+14:00"), "timeString" -> SString("2012-02-15T17:14:27.592+14:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1001"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329020233656")), "timeZone" -> SString("+14:00"), "timeString" -> SString("2012-02-12T18:17:13.656+14:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1017"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329345853072")), "timeZone" -> SString("-02:00"), "timeString" -> SString("2012-02-15T20:44:13.072-02:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1014"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329589296943")), "timeZone" -> SString("+03:00"), "timeString" -> SString("2012-02-18T21:21:36.943+03:00"), "pageId" -> SString("page-3"), "userId" -> SString("user-1006"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1328969812140")), "timeZone" -> SString("+01:00"), "timeString" -> SString("2012-02-11T15:16:52.140+01:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1019"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329211954428")), "timeZone" -> SString("+13:00"), "timeString" -> SString("2012-02-14T22:32:34.428+13:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1020"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329526464104")), "timeZone" -> SString("+13:00"), "timeString" -> SString("2012-02-18T13:54:24.104+13:00"), "pageId" -> SString("page-3"), "userId" -> SString("user-1020"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329190541217")), "timeZone" -> SString("-12:00"), "timeString" -> SString("2012-02-13T15:35:41.217-12:00"), "pageId" -> SString("page-2"), "userId" -> SString("user-1016"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329094347814")), "timeZone" -> SString("+12:00"), "timeString" -> SString("2012-02-13T12:52:27.814+12:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1015"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1328877415620")), "timeZone" -> SString("-12:00"), "timeString" -> SString("2012-02-10T00:36:55.620-12:00"), "pageId" -> SString("page-3"), "userId" -> SString("user-1018"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329456302829")), "timeZone" -> SString("-03:00"), "timeString" -> SString("2012-02-17T02:25:02.829-03:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1001"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329137951622")), "timeZone" -> SString("+04:00"), "timeString" -> SString("2012-02-13T16:59:11.622+04:00"), "pageId" -> SString("page-0"), "userId" -> SString("user-1017"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329360253555")), "timeZone" -> SString("+11:00"), "timeString" -> SString("2012-02-16T13:44:13.555+11:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1020"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1328887823569")), "timeZone" -> SString("+12:00"), "timeString" -> SString("2012-02-11T03:30:23.569+12:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1007"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329076541429")), "timeZone" -> SString("+12:00"), "timeString" -> SString("2012-02-13T07:55:41.429+12:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1016"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329262444197")), "timeZone" -> SString("-06:00"), "timeString" -> SString("2012-02-14T17:34:04.197-06:00"), "pageId" -> SString("page-0"), "userId" -> SString("user-1019"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329004284627")), "timeZone" -> SString("+01:00"), "timeString" -> SString("2012-02-12T00:51:24.627+01:00"), "pageId" -> SString("page-1"), "userId" -> SString("user-1011"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329554034828")), "timeZone" -> SString("+06:00"), "timeString" -> SString("2012-02-18T14:33:54.828+06:00"), "pageId" -> SString("page-2"), "userId" -> SString("user-1016"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329408502943")), "timeZone" -> SString("+13:00"), "timeString" -> SString("2012-02-17T05:08:22.943+13:00"), "pageId" -> SString("page-4"), "userId" -> SString("user-1006"))))
        results must contain(SObject(Map("time" -> SDecimal(BigDecimal("1329629900716")), "timeZone" -> SString("-07:00"), "timeString" -> SString("2012-02-18T22:38:20.716-07:00"), "pageId" -> SString("page-0"), "userId" -> SString("user-1014"))))   
    }

    // Regression test for #39590007
    "give empty results when relation body uses non-existant field" in {
      val input = """
        | clicks := //clicks
        | newClicks := new clicks
        |
        | clicks ~ newClicks
        |   {timeString: clicks.timeString, nonexistant: clicks.nonexistant}""".stripMargin

      eval(input) must beEmpty
    }
    
    "not explode on a large query" in {
      val input = """
        | import std::stats::*
        | import std::time::*
        | 
        | agents := //se/status
        | bin5 := //bins/5
        | allBins := bin5.bin -5
        | 
        | minuteOfDay(time) := (hourOfDay(millisToISO(time, "+00:00")) * 60) + minuteOfHour(millisToISO(time, "+00:00"))
        | 
        | data' := agents with { minuteOfDay: minuteOfDay(agents.timestamp)}
        | 
        | upperBound := getMillis("2012-11-19T23:59:59")
        | lowerBound := getMillis("2012-11-19T00:00:00")
        | extraLB := lowerBound - (60*24*60000)
        | 
        | results := solve 'agent
        |   data'' := data' where data'.timestamp <= upperBound & data'.timestamp >= extraLB & data'.agentId = 'agent
        | 
        |   order := denseRank(data''.timestamp)
        |   data''' := data'' with {rank: order}
        | 
        |   newData := new data'''
        |   newData' := newData with {rank: newData.rank -1}
        | 
        |   result := newData' ~ data''
        | 
        |   {first: data''', second: newData'} where newData'.rank = data'''.rank
        |  
        |   {end: result.second.timestamp, 
        |   status: result.first.status, 
        |   startMinute: result.first.minuteOfDay, 
        |   endMinute: result.second.minuteOfDay}  
        | 
        | results' := results where results.end > lowerBound & results.status = "online"
        | 
        | solve 'bin = allBins.bin
        |   {bin: 'bin, count: count(results'.startMinute where results'.startMinute <= 'bin & results'.endMinute >= 'bin)}
        | """.stripMargin
        
      eval(input) must not(throwAn[Exception])
    }
    
    "solve on a constraint clause defined by an object with two non-const fields" in {
      val input = """
        | clicks := //clicks
        | data := { user: clicks.user, page: clicks.page }
        | 
        | solve 'bins = data
        |   'bins
        | """.stripMargin
      
      evalE(input) must not(throwAn[Exception])
    }
    
    "solve a chaining of user-defined functions involving repeated where clauses" in {
      val input = """
        | import std::time::*
        | import std::stats::*
        | 
        | agents := load("/snapEngage/agents")
        | 
        | upperBound := getMillis("2012-04-03T23:59:59")
        | lowerBound := getMillis("2012-04-03T00:00:00")
        | extraLowerBound := lowerBound - (upperBound - lowerBound)/3 
        | 
        | data := {agentId: agents.agentId, timeStamp: agents.timeStamp, action: agents.action, millis: getMillis(agents.timeStamp)}
        | data' := data where data.millis < upperBound & data.millis > extraLowerBound & data.agentId = "agent1"
        | 
        | 
        | lastEvent(data) := data where data.millis = max(data.millis )
        | --lastEvent(data')
        | 
        | previousEvents(data, millis) := data where data.millis < millis
        | --previous := previousEvents(data', 1333477670000)
        | --last := lastEvent(previous)
        | --last
        | 
        | solve 'time = data'.millis
        |  {end: 'time, start: lastEvent(previousEvents(data', 'time))}
        | """.stripMargin
        
      evalE(input) must not(throwAn[Exception])
    }
    
    "evaluate a trivial inclusion filter" in {
      val input = """
        | t1 := //clicks
        | t2 := //views
        | 
        | t1 ~ t2
        |   t1 where t1.userId = t2.userId
        | """.stripMargin
        
      evalE(input) must not(beEmpty)
    }
    
    "handle a non-trivial solve on an object concat" in {
      val input = """
        | agents := //se/widget
        | 
        | solve 'rank
        |   { agentId: agents.agentId } where { agentId: agents.agentId } = 'rank - 1
        | """.stripMargin
        
      evalE(input) must not(throwAn[Exception])
    }

    "handle array creation with constant dispatch" in {
      val input = """
        | a := 31
        |
        | error := 0.05
        | range(data) := [data*(1-error), data*(1 +error)]
        | range(a)
        | """.stripMargin

      evalE(input) must not(throwAn[Exception])
    }

    "handle object creation with constant dispatch" in {
      val input = """
        | a := 31
        |
        | error := 0.05
        | range(data) := {low: data*(1-error), high: data*(1 +error)}
        | range(a)
        | """.stripMargin

      evalE(input) must not(throwAn[Exception])
    }
     
    "return the non-empty set for a trivial cartesian" in {
      val input = """
        | jobs := //cm
        | titles' := new "foo"
        | 
        | titles' ~ jobs
        |   [titles', jobs.PositionHeader.PositionTitle]
        | """.stripMargin
        
      evalE(input) must not(beEmpty)
    }

    "produce a non-doubled result when counting the union of new sets" in {
      val input = """
        | clicks := //clicks
        | clicks' := new clicks
        |
        | count(clicks' union clicks')
        | """.stripMargin

      eval(input) must contain(SDecimal(100))
    }

    "produce a non-doubled result when counting the union of new sets and a single set" in {
      val input = """
        | clicks := //clicks
        | clicks' := new clicks
        |
        | [count(clicks' union clicks'), count(clicks')]
        | """.stripMargin

      eval(input) must contain(SArray(Vector(SDecimal(100), SDecimal(100))))
    }

    "parse numbers correctly" in {
      val input = """
        | std::string::parseNum("123")
        | """.stripMargin

      evalE(input) mustEqual(Set((Vector(), SDecimal(BigDecimal("123")))))
    }

    "toString numbers correctly" in {
      val input = """
        | std::string::numToString(123)
        | """.stripMargin

      evalE(input) mustEqual(Set((Vector(), SString("123"))))
    }
  }
}

case class Precision(p: Double)
class AlmostEqual(d: Double) {
  def ~=(d2: Double)(implicit p: Precision) = (d - d2).abs <= p.p
}
