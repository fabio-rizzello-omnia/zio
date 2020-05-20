package zio

import zio.random.Random
import zio.test.Assertion._
import zio.test.TestAspect.exceptScala211
import zio.test._

object ChunkSpec extends ZIOBaseSpec {

  case class Value(i: Int) extends AnyVal

  import ZIOTag._

  val intGen = Gen.int(-10, 10)

  def toBoolFn[R <: Random, A]: Gen[R, A => Boolean] =
    Gen.function(Gen.boolean)

  def tinyChunks[R <: Random, A](a: Gen[R, A]): Gen[R, Chunk[A]] =
    Gen.chunkOfBounded(0, 3)(a)

  def smallChunks[R <: Random, A](a: Gen[R, A]): Gen[R with Sized, Chunk[A]] =
    Gen.small(Gen.chunkOfN(_)(a))

  def mediumChunks[R <: Random, A](a: Gen[R, A]): Gen[R with Sized, Chunk[A]] =
    Gen.medium(Gen.chunkOfN(_)(a))

  def largeChunks[R <: Random, A](a: Gen[R, A]): Gen[R with Sized, Chunk[A]] =
    Gen.large(Gen.chunkOfN(_)(a))

  def chunkWithIndex[R <: Random, A](a: Gen[R, A]): Gen[R with Sized, (Chunk[A], Int)] =
    for {
      chunk <- Gen.chunkOfBounded(1, 100)(a)
      idx   <- Gen.int(0, chunk.length - 1)
    } yield (chunk, idx)

  def spec = suite("ChunkSpec")(
    suite("size/length")(
      zio.test.test("concatenated size must match length") {
        val chunk = Chunk.empty ++ Chunk.fromArray(Array(1, 2)) ++ Chunk(3, 4, 5) ++ Chunk.single(6)
        assert(chunk.size)(equalTo(chunk.length))
      },
      zio.test.test("empty size must match length") {
        val chunk = Chunk.empty
        assert(chunk.size)(equalTo(chunk.length))
      },
      zio.test.test("fromArray size must match length") {
        val chunk = Chunk.fromArray(Array(1, 2, 3))
        assert(chunk.size)(equalTo(chunk.length))
      },
      zio.test.test("fromIterable size must match length") {
        val chunk = Chunk.fromIterable(List("1", "2", "3"))
        assert(chunk.size)(equalTo(chunk.length))
      },
      zio.test.test("single size must match length") {
        val chunk = Chunk.single(true)
        assert(chunk.size)(equalTo(chunk.length))
      }
    ),
    suite("add")(
      testM("apply") {
        val chunksWithIndex: Gen[Random with Sized, (Chunk[Int], Chunk[Int], Int)] =
          for {
            p  <- Gen.boolean
            as <- Gen.chunkOf(Gen.anyInt)
            bs <- Gen.chunkOf1(Gen.anyInt)
            n  <- Gen.int(0, as.length + bs.length - 1)
          } yield if (p) (as, bs, n) else (bs, as, n)
        check(chunksWithIndex) {
          case (as, bs, n) =>
            val actual   = bs.foldLeft(as)(_ :+ _).apply(n)
            val expected = (as ++ bs).apply(n)
            assert(actual)(equalTo(expected))
        }
      },
      testM("buffer full") {
        check(Gen.chunkOf(Gen.anyInt), Gen.chunkOf(Gen.anyInt)) { (as, bs) =>
          def addAll[A](l: Chunk[A], r: Chunk[A]): Chunk[A] = r.foldLeft(l)(_ :+ _)
          val actual                                        = List.fill(100)(bs).foldLeft(as)(addAll)
          val expected                                      = List.fill(100)(bs).foldLeft(as)(_ ++ _)
          assert(actual)(equalTo(expected))
        }
      },
      testM("buffer used") {
        checkM(Gen.chunkOf(Gen.anyInt), Gen.chunkOf(Gen.anyInt)) { (as, bs) =>
          val effect   = ZIO.succeed(bs.foldLeft(as)(_ :+ _))
          val actual   = ZIO.collectAllPar(ZIO.replicate(100)(effect))
          val expected = (as ++ bs)
          assertM(actual)(forall(equalTo(expected)))
        }
      },
      testM("equals") {
        check(Gen.chunkOf(Gen.anyInt), Gen.chunkOf(Gen.anyInt)) { (as, bs) =>
          val actual   = bs.foldLeft(as)(_ :+ _)
          val expected = (as ++ bs)
          assert(actual)(equalTo(expected))
        }
      },
      testM("length") {
        check(Gen.chunkOf(Gen.anyInt), smallChunks(Gen.anyInt)) { (as, bs) =>
          val actual   = bs.foldLeft(as)(_ :+ _).length
          val expected = (as ++ bs).length
          assert(actual)(equalTo(expected))
        }
      }
    ),
    testM("apply") {
      check(chunkWithIndex(Gen.unit)) {
        case (chunk, i) =>
          assert(chunk.apply(i))(equalTo(chunk.toList.apply(i)))
      }
    },
    testM("specialized accessors") {
      check(chunkWithIndex(Gen.boolean)) {
        case (chunk, i) =>
          assert(chunk.boolean(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.byte(0, 127))) {
        case (chunk, i) =>
          assert(chunk.byte(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.char(33, 123))) {
        case (chunk, i) =>
          assert(chunk.char(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.short(5, 100))) {
        case (chunk, i) =>
          assert(chunk.short(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.int(1, 142))) {
        case (chunk, i) =>
          assert(chunk.int(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.long(1, 142))) {
        case (chunk, i) =>
          assert(chunk.long(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.double(0.0, 100.0).map(_.toFloat))) {
        case (chunk, i) =>
          assert(chunk.float(i))(equalTo(chunk.toList.apply(i)))
      }
      check(chunkWithIndex(Gen.double(1.0, 200.0))) {
        case (chunk, i) =>
          assert(chunk.double(i))(equalTo(chunk.toList.apply(i)))
      }
    },
    testM("corresponds") {
      val genChunk    = smallChunks(intGen)
      val genFunction = Gen.function[Random with Sized, (Int, Int), Boolean](Gen.boolean).map(Function.untupled(_))
      check(genChunk, genChunk, genFunction) { (as, bs, f) =>
        val actual   = as.corresponds(bs)(f)
        val expected = as.toList.corresponds(bs.toList)(f)
        assert(actual)(equalTo(expected))
      }
    },
    testM("fill") {
      val smallInt = Gen.int(-10, 10)
      check(smallInt, smallInt) { (n, elem) =>
        val actual   = Chunk.fill(n)(elem)
        val expected = Chunk.fromArray(Array.fill(n)(elem))
        assert(actual)(equalTo(expected))
      }
    },
    zio.test.test("splitWhere") {
      assert(Chunk(1, 2, 3, 4).splitWhere(_ == 2))(equalTo((Chunk(1), Chunk(2, 3, 4))))
    },
    testM("length") {
      check(largeChunks(intGen))(chunk => assert(chunk.length)(equalTo(chunk.toList.length)))
    },
    testM("equality") {
      check(mediumChunks(intGen), mediumChunks(intGen)) { (c1, c2) =>
        assert(c1.equals(c2))(equalTo(c1.toList.equals(c2.toList)))
      }
    },
    zio.test.test("inequality") {
      assert(Chunk(1, 2, 3, 4, 5))(Assertion.not(equalTo(Chunk(1, 2, 3, 4, 5, 6))))
    },
    testM("materialize") {
      check(mediumChunks(intGen))(c => assert(c.materialize.toList)(equalTo(c.toList)))
    },
    testM("foldLeft") {
      check(Gen.alphaNumericString, Gen.function2(Gen.alphaNumericString), smallChunks(Gen.alphaNumericString)) {
        (s0, f, c) => assert(c.fold(s0)(f))(equalTo(c.toArray.foldLeft(s0)(f)))
      }
    },
    zio.test.test("mapAccum") {
      assert(Chunk(1, 1, 1).mapAccum(0)((s, el) => (s + el, s + el)))(equalTo((3, Chunk(1, 2, 3))))
    },
    suite("mapAccumM")(
      testM("mapAccumM happy path") {
        assertM(Chunk(1, 1, 1).mapAccumM(0)((s, el) => UIO.succeed((s + el, s + el))))(equalTo((3, Chunk(1, 2, 3))))
      },
      testM("mapAccumM error") {
        Chunk(1, 1, 1).mapAccumM(0)((_, _) => IO.fail("Ouch")).either.map(assert(_)(isLeft(equalTo("Ouch"))))
      } @@ zioTag(errors)
    ),
    testM("map") {
      val fn = Gen.function[Random with Sized, Int, Int](intGen)
      check(smallChunks(intGen), fn)((c, f) => assert(c.map(f).toList)(equalTo(c.toList.map(f))))
    },
    suite("mapM")(
      testM("mapM happy path")(checkM(mediumChunks(intGen), Gen.function(Gen.boolean)) { (chunk, f) =>
        chunk.mapM(s => UIO.succeed(f(s))).map(assert(_)(equalTo(chunk.map(f))))
      }),
      testM("mapM error") {
        Chunk(1, 2, 3).mapM(_ => IO.fail("Ouch")).either.map(assert(_)(equalTo(Left("Ouch"))))
      } @@ zioTag(errors)
    ),
    testM("flatMap") {
      val fn = Gen.function[Random with Sized, Int, Chunk[Int]](smallChunks(intGen))
      check(smallChunks(intGen), fn) { (c, f) =>
        assert(c.flatMap(f).toList)(equalTo(c.toList.flatMap(f.andThen(_.toList))))
      }
    },
    testM("headOption") {
      check(mediumChunks(intGen))(c => assert(c.headOption)(equalTo(c.toList.headOption)))
    },
    testM("lastOption") {
      check(mediumChunks(intGen))(c => assert(c.lastOption)(equalTo(c.toList.lastOption)))
    },
    testM("indexWhere") {
      val fn = Gen.function[Random with Sized, Int, Boolean](Gen.boolean)
      check(mediumChunks(intGen), fn, intGen) { (chunk, p, from) =>
        assert(chunk.indexWhere(p, from))(equalTo(chunk.toList.indexWhere(p, from)))
      }
    } @@ exceptScala211,
    testM("exists") {
      val fn = Gen.function[Random with Sized, Int, Boolean](Gen.boolean)
      check(mediumChunks(intGen), fn)((chunk, p) => assert(chunk.exists(p))(equalTo(chunk.toList.exists(p))))
    },
    testM("forall") {
      val fn = Gen.function[Random with Sized, Int, Boolean](Gen.boolean)
      check(mediumChunks(intGen), fn)((chunk, p) => assert(chunk.forall(p))(equalTo(chunk.toList.forall(p))))
    },
    testM("find") {
      val fn = Gen.function[Random with Sized, Int, Boolean](Gen.boolean)
      check(mediumChunks(intGen), fn)((chunk, p) => assert(chunk.find(p))(equalTo(chunk.toList.find(p))))
    },
    testM("filter") {
      val fn = Gen.function[Random with Sized, Int, Boolean](Gen.boolean)
      check(mediumChunks(intGen), fn)((chunk, p) => assert(chunk.filter(p).toList)(equalTo(chunk.toList.filter(p))))
    },
    suite("filterM")(
      testM("filterM happy path")(checkM(mediumChunks(intGen), Gen.function(Gen.boolean)) { (chunk, p) =>
        chunk.filterM(s => UIO.succeed(p(s))).map(assert(_)(equalTo(chunk.filter(p))))
      }),
      testM("filterM error") {
        Chunk(1, 2, 3).filterM(_ => IO.fail("Ouch")).either.map(assert(_)(equalTo(Left("Ouch"))))
      } @@ zioTag(errors)
    ),
    testM("drop chunk") {
      check(largeChunks(intGen), intGen)((chunk, n) => assert(chunk.drop(n).toList)(equalTo(chunk.toList.drop(n))))
    },
    testM("take chunk") {
      check(chunkWithIndex(Gen.unit)) {
        case (c, n) =>
          assert(c.take(n).toList)(equalTo(c.toList.take(n)))
      }
    },
    testM("dropWhile chunk") {
      check(mediumChunks(intGen), toBoolFn[Random, Int]) { (c, p) =>
        assert(c.dropWhile(p).toList)(equalTo(c.toList.dropWhile(p)))
      }
    },
    suite("dropWhileM")(
      testM("dropWhileM happy path") {
        assertM(Chunk(1, 2, 3, 4, 5).dropWhileM(el => UIO.succeed(el % 2 == 1)))(equalTo(Chunk(2, 3, 4, 5)))
      },
      testM("dropWhileM error") {
        Chunk(1, 1, 1).dropWhileM(_ => IO.fail("Ouch")).either.map(assert(_)(isLeft(equalTo("Ouch"))))
      } @@ zioTag(errors)
    ),
    testM("takeWhile chunk") {
      check(mediumChunks(intGen), toBoolFn[Random, Int]) { (c, p) =>
        assert(c.takeWhile(p).toList)(equalTo(c.toList.takeWhile(p)))
      }
    },
    testM("toArray") {
      check(mediumChunks(Gen.alphaNumericString))(c => assert(c.toArray.toList)(equalTo(c.toList)))
    },
    zio.test.test("non-homogeneous element type") {
      trait Animal
      trait Cat extends Animal
      trait Dog extends Animal

      val vector   = Vector(new Cat {}, new Dog {}, new Animal {})
      val actual   = Chunk.fromIterable(vector).map(identity)
      val expected = Chunk.fromArray(vector.toArray)

      assert(actual)(equalTo(expected))
    },
    zio.test.test("toArray for an empty Chunk of type String") {
      assert(Chunk.empty.toArray[String])(equalTo(Array.empty[String]))
    },
    zio.test.test("to Array for an empty Chunk using filter") {
      assert(Chunk(1).filter(_ == 2).map(_.toString).toArray[String])(equalTo(Array.empty[String]))
    },
    testM("toArray with elements of type String") {
      check(mediumChunks(Gen.alphaNumericString))(c => assert(c.toArray.toList)(equalTo(c.toList)))
    },
    zio.test.test("toArray for a Chunk of any type") {
      val v: Vector[Any] = Vector("String", 1, Value(2))
      assert(Chunk.fromIterable(v).toArray.toVector)(equalTo(v))
    },
    suite("collect")(
      zio.test.test("collect empty Chunk") {
        assert(Chunk.empty.collect { case _ => 1 })(isEmpty)
      },
      testM("collect chunk") {
        val pfGen = Gen.partialFunction[Random with Sized, Int, Int](intGen)
        check(mediumChunks(intGen), pfGen)((c, pf) => assert(c.collect(pf).toList)(equalTo(c.toList.collect(pf))))
      }
    ),
    suite("collectM")(
      testM("collectM empty Chunk") {
        assertM(Chunk.empty.collectM { case _ => UIO.succeed(1) })(equalTo(Chunk.empty))
      },
      testM("collectM chunk") {
        val pfGen = Gen.partialFunction[Random with Sized, Int, UIO[Int]](Gen.successes(intGen))
        checkM(mediumChunks(intGen), pfGen) { (c, pf) =>
          for {
            result   <- c.collectM(pf).map(_.toList)
            expected <- UIO.collectAll(c.toList.collect(pf))
          } yield assert(result)(equalTo(expected))
        }
      },
      testM("collectM chunk that fails") {
        Chunk(1, 2).collectM { case 2 => IO.fail("Ouch") }.either.map(assert(_)(isLeft(equalTo("Ouch"))))
      } @@ zioTag(errors)
    ),
    suite("collectWhile")(
      zio.test.test("collectWhile empty Chunk") {
        assert(Chunk.empty.collectWhile { case _ => 1 })(isEmpty)
      },
      testM("collectWhile chunk") {
        val pfGen = Gen.partialFunction[Random with Sized, Int, Int](intGen)
        check(mediumChunks(intGen), pfGen) { (c, pf) =>
          assert(c.collectWhile(pf).toList)(equalTo(c.toList.takeWhile(pf.isDefinedAt).map(pf.apply)))
        }
      }
    ),
    suite("collectWhileM")(
      testM("collectWhileM empty Chunk") {
        assertM(Chunk.empty.collectWhileM { case _ => UIO.succeed(1) })(equalTo(Chunk.empty))
      },
      testM("collectWhileM chunk") {
        val pfGen = Gen.partialFunction[Random with Sized, Int, UIO[Int]](Gen.successes(intGen))
        checkM(mediumChunks(intGen), pfGen) { (c, pf) =>
          for {
            result   <- c.collectWhileM(pf).map(_.toList)
            expected <- UIO.foreach(c.toList.takeWhile(pf.isDefinedAt))(pf.apply)
          } yield assert(result)(equalTo(expected))
        }
      },
      testM("collectWhileM chunk that fails") {
        Chunk(1, 2).collectWhileM { case _ => IO.fail("Ouch") }.either.map(assert(_)(isLeft(equalTo("Ouch"))))
      } @@ zioTag(errors)
    ),
    testM("foreach") {
      check(mediumChunks(intGen)) { c =>
        var sum = 0
        c.foreach(sum += _)

        assert(sum)(equalTo(c.toList.sum))
      }
    },
    testM("concat chunk") {
      check(smallChunks(intGen), smallChunks(intGen)) { (c1, c2) =>
        assert((c1 ++ c2).toList)(equalTo(c1.toList ++ c2.toList))
      }
    },
    zio.test.test("chunk transitivity") {
      val c1 = Chunk(1, 2, 3)
      val c2 = Chunk(1, 2, 3)
      val c3 = Chunk(1, 2, 3)
      assert(c1 == c2 && c2 == c3 && c1 == c3)(Assertion.isTrue)
    },
    zio.test.test("chunk symmetry") {
      val c1 = Chunk(1, 2, 3)
      val c2 = Chunk(1, 2, 3)
      assert(c1 == c2 && c2 == c1)(Assertion.isTrue)
    },
    zio.test.test("chunk reflexivity") {
      val c1 = Chunk(1, 2, 3)
      assert(c1 == c1)(Assertion.isTrue)
    },
    zio.test.test("chunk negation") {
      val c1 = Chunk(1, 2, 3)
      val c2 = Chunk(1, 2, 3)
      assert(c1 != c2 == !(c1 == c2))(Assertion.isTrue)
    },
    zio.test.test("chunk substitutivity") {
      val c1 = Chunk(1, 2, 3)
      val c2 = Chunk(1, 2, 3)
      assert(c1 == c2 && c1.toString == c2.toString)(Assertion.isTrue)
    },
    zio.test.test("chunk consistency") {
      val c1 = Chunk(1, 2, 3)
      val c2 = Chunk(1, 2, 3)
      assert(c1 == c2 && c1.hashCode == c2.hashCode)(Assertion.isTrue)
    },
    zio.test.test("nullArrayBug") {
      val c = Chunk.fromArray(Array(1, 2, 3, 4, 5))

      // foreach should not throw
      c.foreach(_ => ())

      assert(c.filter(_ => false).map(_ * 2).length)(equalTo(0))
    },
    zio.test.test("toArrayOnConcatOfSlice") {
      val onlyOdd: Int => Boolean = _ % 2 != 0
      val concat = Chunk(1, 1, 1).filter(onlyOdd) ++
        Chunk(2, 2, 2).filter(onlyOdd) ++
        Chunk(3, 3, 3).filter(onlyOdd)

      val array = concat.toArray

      assert(array)(equalTo(Array(1, 1, 1, 3, 3, 3)))
    },
    zio.test.test("toArrayOnConcatOfEmptyAndInts") {
      assert(Chunk.empty ++ Chunk.fromArray(Array(1, 2, 3)))(equalTo(Chunk(1, 2, 3)))
    },
    zio.test.test("filterConstFalseResultsInEmptyChunk") {
      assert(Chunk.fromArray(Array(1, 2, 3)).filter(_ => false))(equalTo(Chunk.empty))
    },
    zio.test.test("zipAllWith") {
      assert(Chunk(1, 2, 3).zipAllWith(Chunk(3, 2, 1))(_ => 0, _ => 0)(_ + _))(equalTo(Chunk(4, 4, 4))) &&
      assert(Chunk(1, 2, 3).zipAllWith(Chunk(3, 2))(_ => 0, _ => 0)(_ + _))(equalTo(Chunk(4, 4, 0))) &&
      assert(Chunk(1, 2).zipAllWith(Chunk(3, 2, 1))(_ => 0, _ => 0)(_ + _))(equalTo(Chunk(4, 4, 0)))
    },
    zio.test.test("partitionMap") {
      val as       = Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      val (bs, cs) = as.partitionMap(n => if (n % 2 == 0) Left(n) else Right(n))
      assert(bs)(equalTo(Chunk(0, 2, 4, 6, 8))) &&
      assert(cs)(equalTo(Chunk(1, 3, 5, 7, 9)))
    }
  )
}