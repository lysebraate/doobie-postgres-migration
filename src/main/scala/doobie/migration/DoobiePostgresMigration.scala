package doobie.migration

import java.io.File
import java.security.MessageDigest

import org.slf4j.LoggerFactory
import cats.effect.IO
import doobie.ConnectionIO
import doobie.util.transactor.Transactor.Aux

import scala.io.Source

case class DoobiePostgresMigrationException(msg: String, ex: Exception = null) extends Exception(msg, ex)

/**
  * Represents a DB schema migration
  *
  * @param id a unique id string that must be sorted higher than the last one applied. It has to be a number of 10 digits followed by a '_' and a string. By convention the number is the UTC time when it was created.
  * @param up the sql that will be applied to go 'up' or to the next version of the DB schema
  * @param down the sql that will be applied to rollback one version from current version of DB schema
  */
case class Migration(id: String, up: String, down: String)

/**
  * Schema migrations for Postgres using doobie
  */
object DoobiePostgresMigration {
  private val MigrationFileRegex = """^(\d{10}_.*)\.(up|down)\.sql$""".r
  private lazy val logger = LoggerFactory.getLogger(getClass)

  /**
    * Execute all migrations based in the directory. This will apply downs for any files present.
    * @param migrationsDir
    * @param xa
    * @param downMode if true, downs WILL be applied (so: downMode should be disabled in prod)
    * @param schema the name of the database schema (not the schema migrations)
    */
  def execute(migrationsDir: File, xa: Aux[IO, _], downMode: Boolean, schema: String = "public"): Unit = {
    try {
      executeMigrationsIO(migrationsDir, xa, downMode, schema).unsafeRunSync()
    } catch {
      case ex : Exception =>
        logger.error(s"Could not apply schema migrations:\n${ex.getMessage}", ex)
    }
  }

  def executeMigrationsIO(migrationsDir: File, xa: Aux[IO, _], downMode: Boolean, schema: String): IO[List[Migration]] = {
    import doobie.implicits._
    for {
      migrations <- getMigrations(migrationsDir)
      _ <- applyMigrations(migrations, downMode, schema).transact(xa)
    } yield migrations
  }

  private def idToUpFilePath(id: String) = id + ".up.sql"
  private def idToDownFilePath(id: String) = id + ".down.sql"

  private sealed trait MigrationFromFile {
    val id: String
  }
  private case class MigrationFromFileUp(id: String, up: String) extends MigrationFromFile
  private case class MigrationFromFileDown(id: String, down: String) extends MigrationFromFile

  def getMigrations(migrationsDir: File): IO[List[Migration]] = {
    import cats.effect.IO._
    import cats.implicits._

    def readLinesAsIO(file: File) = IO(Source.fromFile(file).getLines.mkString("\n"))

    def getDataAndValidateMatchingFilename(file: File): IO[MigrationFromFile] = file.getName match {
      case MigrationFileRegex(id, "up") => for {
        content <- readLinesAsIO(file) // <- read up files
      } yield MigrationFromFileUp(id, content)
      case MigrationFileRegex(id, "down") => for {
        content <- readLinesAsIO((file))  // <- read down files
      } yield MigrationFromFileDown(id, content)
      case _ =>  // validate that there's only filenames matching our filename schema
        raiseError(DoobiePostgresMigrationException(s"Found non-matching filename '${file.getAbsolutePath}'. All files in dir: '${migrationsDir.getAbsolutePath}' must match regex: ${MigrationFileRegex}"))
    }

    def getFileMigrationsAndValidateFileCount(eitherUpOrDown: List[MigrationFromFile]): IO[List[Migration]] = {
      val upAndDownsById = eitherUpOrDown.groupBy(_.id).toList
      upAndDownsById.traverse { case (id, upsAndDowns) =>
        val manyUps = upsAndDowns.filter {
          case MigrationFromFileUp(_, _) => true
          case _ => false
        }
        val manyDowns = upsAndDowns.filter {
          case MigrationFromFileDown(_, _) => true
          case _ => false
        }
        if (manyDowns.length <= 1 || manyUps.length <= 1) {
          val fileMigration = for {
            // these matches should never fail because manyUps/Downs should be 1 here
            MigrationFromFileUp(`id`, up) <- manyUps.headOption
            MigrationFromFileDown(`id`, down) <- manyDowns.headOption
          } yield Migration(id, up, down)
          fileMigration.map(IO.apply(_)).getOrElse {
            val upAbsolutePath = new File(migrationsDir, idToUpFilePath(id)).getAbsolutePath
            val downAbsolutePath = new File(migrationsDir, idToDownFilePath(id)).getAbsolutePath
            if (manyUps.length == 0 && manyDowns.length == 0) {
              raiseError(DoobiePostgresMigrationException(s"Missing both up and down for id: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            } else if (manyUps.length == 0) {
              raiseError(DoobiePostgresMigrationException(s"Missing an .up file for id: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            } else { // manyDowns.length == 0
              raiseError(DoobiePostgresMigrationException(s"Missing a .down file for: $id. Searched files: $upAbsolutePath and $downAbsolutePath"))
            }
          }
        } else if (manyDowns.length > 1 || manyUps.length > 1) {
          raiseError(DoobiePostgresMigrationException(s"Found many downs/ups for: $id in dir: '${migrationsDir.getAbsolutePath}'"))
        } else {
          raiseError(DoobiePostgresMigrationException(s"Did not find EXACTLY these files: ${idToUpFilePath(id)} and ${idToDownFilePath(id)} in dir: '${migrationsDir.getAbsolutePath}'"))
        }
      }
    }

    val ioFiles = {
      Option(migrationsDir.listFiles()).map(IO.apply(_)).getOrElse {
        raiseError(DoobiePostgresMigrationException(s"Could not read files from migrations directory: ${migrationsDir.getAbsolutePath}"))
      }
    }

    for {
      files <- ioFiles
      fileMigrationTuples <- files.foldLeft(IO(List.empty[MigrationFromFile])) { (prevIO, curr) =>
          for {
            prev <- prevIO
            fileMigrationTuple <- getDataAndValidateMatchingFilename(curr)
          } yield prev :+ fileMigrationTuple
      }
      schemaFiles <- getFileMigrationsAndValidateFileCount(fileMigrationTuples)
    } yield schemaFiles.sortBy(_.id)
  }

  private case class IdDown(id: String, down: String)
  private case class IdMd5(id: String, md5: String)

  private def md5Hash(str: String): String = {
    MessageDigest.getInstance("MD5").digest(str.getBytes).map("%02X".format(_)).mkString
  }

  def applyMigrations(migrations: List[Migration], downMode: Boolean, schema: String): ConnectionIO[_] = {
    import doobie._
    import doobie.FC.{ delay,raiseError, unit }
    import cats.implicits._
    import doobie.implicits._
    import doobie.postgres.implicits._

    val createSchemaMigration =
      (sql"""|CREATE TABLE IF NOT EXISTS """ ++ Fragment.const(schema + ".schema_migration") ++ sql""" (
            |  id TEXT PRIMARY KEY,
            |  md5 TEXT NOT NULL,
            |  up TEXT NOT NULL,
            |  down TEXT NOT NULL,
            |  created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
            |)
        """).stripMargin.update.run
    val validateSchemaSql =
      sql"""|SELECT 5 = (
            |  SELECT COUNT(*) FROM information_schema.columns
            |  WHERE table_name = 'schema_migration' AND table_schema = $schema AND (
            |    (lower(column_name) = 'id' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'md5' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'up' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'down' AND upper(data_type) = 'TEXT' AND upper(is_nullable) = 'NO')
            |    OR
            |    (lower(column_name) = 'created_at' AND upper(data_type) = 'TIMESTAMP WITHOUT TIME ZONE' AND upper(is_nullable) = 'NO')
            |  )
            |)
        """.stripMargin
    val fileIds = migrations.map(_.id)
    for {
      // step 1: create schema_migration.
      //         We CREATE IF NOT EXISTS because it is fine if it is already there.
      _ <- createSchemaMigration

      // step 2: validate that schema_migration is not bogus.
      //         We do this because we could have skipped it earlier since we do CREATE IF NOT EXISTS.
      validated <- validateSchemaSql.query[Boolean].unique
      _ <- if (!validated) {
        raiseError(DoobiePostgresMigrationException(s"Could not validate `schema_migration` table via: $validateSchemaSql"))
      } else unit

      // step 3: apply all downs that are not present in our dir.
      downMigrations <-
        sql"""|SELECT id, down FROM schema_migration
              |WHERE NOT id = ANY($fileIds)
              |ORDER BY id DESC
              |""".stripMargin.query[IdDown].to[List]
      _ <- if (downMigrations.nonEmpty && !downMode) {
        raiseError(DoobiePostgresMigrationException("Cannot apply down migrations unless down mode is enabled"))
      } else unit
      _ <- downMigrations.traverse[ConnectionIO, Unit] { curr =>
        val id = curr.id
        for {
          _ <- delay(logger.warn(s"Applying downs from: ${idToDownFilePath(id)}!"))
          _ <- Update0(curr.down, None).run.handleErrorWith { case ex: Exception =>
              raiseError(DoobiePostgresMigrationException(s"Failed while applying downs from '${idToDownFilePath(id)}':\n${curr.down}", ex))
          }
          _ <- sql"""|DELETE FROM schema_migration
                     |WHERE id = $id
                     |""".stripMargin.update.run
        } yield ()
      }

      // step 4.1: get existing migrations
      dBIdAndMd5 <-
        sql"""|SELECT id, md5 FROM schema_migration
              |WHERE id = ANY($fileIds)
              |ORDER BY id DESC
              |""".stripMargin.query[IdMd5].to[List]
      hashByIdFromDb = dBIdAndMd5.groupBy(_.id)
      dbIds = dBIdAndMd5.map(_.id)
      highestCurrentId = if (dbIds.nonEmpty) {
        dbIds.max
      } else dbIds.headOption.getOrElse("")


      // step 4.2: apply up migrations and check existing ones
      _ <- migrations.traverse[ConnectionIO, Unit] { curr =>
        val id = curr.id
        val fileHash = md5Hash(id + curr.up + curr.down)
        hashByIdFromDb.get(id) match {
          case None => // DB didn't know about this file, so we will apply ups
            val upMigrations = for {
              _ <- Update0(curr.up, None).run.handleErrorWith { case ex: Exception =>
                raiseError(DoobiePostgresMigrationException(s"Failed while applying ups from '$id':\n${curr.up}", ex))
              }
              _ <- if (id < highestCurrentId) {
                raiseError(DoobiePostgresMigrationException(s"Cannot apply migration! Id: '$id' is 'lower' than: '$highestCurrentId'"))
              } else unit
              _ <-
                sql"""|INSERT INTO schema_migration(id, md5, up, down)
                      |VALUES ($id, $fileHash, ${curr.up}, ${curr.down})
                      |""".stripMargin.update.run
            } yield ()
            for {
              _ <- delay(logger.info(s"Applying ups from '${idToUpFilePath(id)}'..."))
              _ <- upMigrations
            } yield ()
          case Some(Seq(IdMd5(`id`, `fileHash`))) =>
            delay(logger.debug(s"Schema and id matches for '$id'. Skipping..."))
          case Some(Seq(IdMd5(`id`, md5))) =>
            raiseError(DoobiePostgresMigrationException(s"Wrong hash for '$id'! DB says: '$md5'. Current is: '$fileHash'. Did files: ${idToDownFilePath(id)} and ${idToUpFilePath(id)} change?"))
          case Some(alts) =>
            raiseError(DoobiePostgresMigrationException(s"Expected to find one or zero pairs with id: '$id', but found: ${alts.mkString(",")}")) // can only happen if id is no longer a PRIMARY KEY
        }
      }
    } yield ()
  }
}
