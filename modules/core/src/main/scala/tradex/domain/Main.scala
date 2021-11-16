package tradex.domain

import zio._
import zio.console.putStrLn

import services._
import services.trading.TradingService._
import java.time._
import model.account._

object Main extends zio.App {
  def run(args: List[String]): zio.URIO[zio.ZEnv, zio.ExitCode] = {
    val program = for {
      // dbconf <- ZIO.access[config.Config](_.dbConfig)
      // _      <- FlywayMigration.migrate(dbconf)
      accounts <- makeProgram
    } yield accounts

    program
      .map(accs => println(accs.size.toString))
      .tapError(err => putStrLn(s"Execution failed with: ${err.getMessage}"))
      .exitCode
  }

  val module: ZIO[TradingService, TradingError, List[Account]] = for {
    accounts <- getAccountsOpenedOn(LocalDate.EPOCH)
  } yield accounts

  val makeProgram = module.provideLayer(Application.prod.appLayer)
  /*
  val dbconf      = ZIO.access[config.Config](_.dbConfig)
  for {
    d <- dbconf
  } yield d
   */
}
