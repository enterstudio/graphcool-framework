package cool.graph.deploy.migration.migrator

import akka.actor.{Actor, ActorRef, Props, Stash, Terminated}
import cool.graph.deploy.database.persistence.{MigrationPersistence, ProjectPersistence}
import cool.graph.shared.models.{Migration, MigrationStep, Project}
import akka.pattern.pipe
import cool.graph.deploy.migration.{MigrationApplier, MigrationApplierImpl}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

trait Migrator {
  def schedule(nextProject: Project, steps: Vector[MigrationStep]): Future[Migration]
}

object Initialize
case class Schedule(nextProject: Project, steps: Vector[MigrationStep])

case class DeploymentSchedulerActor()(
    implicit val migrationPersistence: MigrationPersistence,
    projectPersistence: ProjectPersistence
) extends Actor
    with Stash {
  implicit val dispatcher = context.system.dispatcher
  val projectWorkers      = new mutable.HashMap[String, ActorRef]()

  // Enhancement(s): In the shared cluster we might face issues with too many project actors / high overhead during bootup
  //    - We could have a last active timestamp or something and if a limit is reached we reap project actors.
  // How to handle graceful shutdown? -> Unwatch, stop message, wait for completion?

  def receive: Receive = {
    case Initialize =>
      val caller = sender()
      initialize().onComplete {
        case Success(_) =>
          caller ! akka.actor.Status.Success(())
          context.become(ready)
          unstashAll()

        case Failure(err) =>
          caller ! akka.actor.Status.Failure(err)
          context.stop(self)
      }

    case _ =>
      stash()
  }

  def ready: Receive = {
    case msg: Schedule       => scheduleMigration(msg)
    case Terminated(watched) => handleTerminated(watched)
  }

  def initialize(): Future[Unit] = {
    projectPersistence.loadProjectsWithUnappliedMigrations().transformWith {
      case Success(projects) => Future { projects.foreach(project => workerForProject(project.id)) }
      case Failure(err)      => Future.failed(err)
    }
  }

  def scheduleMigration(scheduleMsg: Schedule): Unit = {
    val workerRef = projectWorkers.get(scheduleMsg.nextProject.id) match {
      case Some(worker) => worker
      case None         => workerForProject(scheduleMsg.nextProject.id)
    }

    workerRef.tell(scheduleMsg, sender())
  }

  def workerForProject(projectId: String): ActorRef = {
    val newWorker = context.actorOf(Props(ProjectDeploymentActor(projectId)))

    context.watch(newWorker)
    projectWorkers += (projectId -> newWorker)
    newWorker
  }

  def handleTerminated(watched: ActorRef) = {
    projectWorkers.find(_._2 == watched) match {
      case Some((pid, _)) =>
        println(s"[Warning] Worker for project $pid terminated abnormally. Recreating...")
        workerForProject(pid)

      case None =>
        println(s"[Warning] Terminated child actor $watched has never been mapped to a project.")
    }
  }
}

object WorkoffDeployment
object ResumeMessageProcessing
object Ready

// Todo only saves for now, doesn't work off (that is still in the applier job!)
case class ProjectDeploymentActor(projectID: String)(
    implicit val migrationPersistence: MigrationPersistence,
    applier: MigrationApplier
) extends Actor
    with Stash {
  implicit val ec = context.system.dispatcher

  // Inactive until signal?
  // Possible enhancement: Periodically scan the DB for migrations if signal was lost?
  // How to retry failed migrations?
  // LastRevisionSeen as a safety net?

  initialize()

  def receive: Receive = {
    case Ready =>
      context.become(ready)
      unstashAll()

    case _ =>
      stash()
  }

  def ready: Receive = {
    case Schedule(nextProject, steps) =>
      migrationPersistence.create(nextProject, Migration(nextProject, steps)) pipeTo sender()
      self ! WorkoffDeployment

    // work off replaces the actor behavior until the messages has been processed, as it is async and we need
    // to keep message processing sequential and consistent, but async for best performance
    case WorkoffDeployment =>
      context.become(busy)

    // How to get migration progress into the picture?
    //
  }

  def busy: Receive = {
    case ResumeMessageProcessing => context.unbecome()
    case _                       => stash()
  }

  def initialize() = {
    // Load all unapplied migrations for project and schedule as many workoff messages
    // Load all migrations from DB on init and queue them as messages, or Just schedule messages that something needs working off (more robust, not that much more overhead)
    // => Later with the new the new migration progress, we need to go to the DB anyways to set the status.
    // => This way we could check that the next one is the correct one...

    self ! Ready
  }
}