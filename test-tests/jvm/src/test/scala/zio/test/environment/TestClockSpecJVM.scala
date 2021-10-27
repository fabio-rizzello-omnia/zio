package zio.test.environment

import zio._
import zio.test.Assertion._
import zio.test._

import java.util.concurrent.TimeUnit

object TestClockSpecJVM extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("TestClockSpecJVM")(
      suite("asScheduledExecutorService")(
        test("schedules tasks at fixed rate correctly") {
          for {
            runtime                 <- ZIO.runtime[Has[Clock]]
            ref                     <- Ref.make[List[Long]](List.empty)
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleAtFixedRate(
                     new Runnable {
                       def run(): Unit =
                         runtime.unsafeRun {
                           ZIO.sleep(2.seconds) *>
                             Clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                         }
                     },
                     3,
                     5,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(25.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L, 10L, 15L, 20L, 25L)))
        },
        test("does not allow tasks to pile up") {
          for {
            runtime                 <- ZIO.runtime[Has[Clock]]
            ref                     <- Ref.make[List[Long]](List.empty)
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleAtFixedRate(
                     new Runnable {
                       def run(): Unit =
                         runtime.unsafeRun {
                           ZIO.sleep(5.seconds) *>
                             Clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                         }
                     },
                     3,
                     2,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(28.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(8L, 13L, 18L, 23L, 28L)))
        },
        test("schedules tasks with fixed delay correctly") {
          for {
            runtime                 <- ZIO.runtime[Has[Clock]]
            ref                     <- Ref.make[List[Long]](List.empty)
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleWithFixedDelay(
                     new Runnable {
                       def run(): Unit =
                         runtime.unsafeRun {
                           ZIO.sleep(2.seconds) *>
                             Clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                         }
                     },
                     3,
                     5,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(33.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L, 12L, 19L, 26L, 33L)))
        },
        test("allows scheduled tasks to be interrupted") {
          for {
            runtime                 <- ZIO.runtime[Has[Clock]]
            ref                     <- Ref.make[List[Long]](List.empty)
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            future <- ZIO.succeed {
                        scheduledExecutorService.scheduleAtFixedRate(
                          new Runnable {
                            def run(): Unit =
                              runtime.unsafeRun {
                                ZIO.sleep(2.seconds) *>
                                  Clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                              }
                          },
                          3,
                          5,
                          TimeUnit.SECONDS
                        )
                      }
            _      <- TestClock.adjust(7.seconds)
            _      <- ZIO.succeed(future.cancel(false))
            _      <- TestClock.adjust(11.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L)))
        }
      )
    ) @@ TestAspect.nonFlaky
}