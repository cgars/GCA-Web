package service

import java.util.UUID
import javax.persistence.{NoResultException, TypedQuery}

import com.mohiva.play.silhouette.contrib.daos.DelegableAuthInfoDAO
import com.mohiva.play.silhouette.core.LoginInfo
import com.mohiva.play.silhouette.core.providers.PasswordInfo
import com.mohiva.play.silhouette.core.services.IdentityService
import com.mohiva.play.silhouette.core.utils.PasswordHasher
import models.{Account, CredentialsLogin, Login}
import org.joda.time.DateTime
import plugins.DBUtil._
import service.mail.MailerService
import utils.DefaultRoutesResolver

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class AccountStore(val pwHasher: PasswordHasher) {

  val resolver = DefaultRoutesResolver.resolver
  val mailerService = new MailerService

  def get(id: String, requireActive: Boolean = true) : Account = {
    query { em =>
      val queryStr =
        """SELECT DISTINCT a FROM Account a
           LEFT JOIN FETCH a.logins l
           WHERE a.uuid = :uuid AND (l.isActive = true OR :require = false)"""

      val query: TypedQuery[Account] = em.createQuery(queryStr, classOf[Account])
      query.setParameter("uuid", id)
      query.setParameter("require", requireActive)

      query.getSingleResult
    }
  }

  def getByMail(mail: String): Account = {
    query { em =>
      val queryStr =
        """SELECT DISTINCT a FROM Account a
           LEFT JOIN FETCH a.logins l
           WHERE LOWER(a.mail) = LOWER(:email) AND l.isActive = true"""

      val query: TypedQuery[Account] = em.createQuery(queryStr, classOf[Account])
      query.setParameter("email", mail)

      query.getSingleResult
    }
  }

  def list(): Seq[Account] = {
    query { em =>
      val queryStr =
        """SELECT DISTINCT a FROM Account a
           LEFT JOIN a.logins l
           WHERE l.isActive = true"""

      val query: TypedQuery[Account] = em.createQuery(queryStr, classOf[Account])

      asScalaBuffer(query.getResultList)
    }

  }

  def create(account: Account, plainPassword : Option[String] = None): Account = {
    val created = transaction { (em, tx) =>
      val queryStr =
        """SELECT DISTINCT a FROM Account a
           LEFT JOIN FETCH a.logins l
           WHERE LOWER(a.mail) = LOWER(:email)"""

      val query: TypedQuery[Account] = em.createQuery(queryStr, classOf[Account])
      query.setParameter("email", account.mail)

      val existing = query.getResultList
      if (! existing.isEmpty)
        // TODO define a better exception and forward the user to the reset pw page
        throw new RuntimeException(s"An account with '${account.mail}' already exists!")

      account.logins.clear()
      var now = new DateTime()
      account.ctime = now
      account.mtime = now

      plainPassword.foreach { pw =>
        val token = UUID.randomUUID.toString
        account.logins.add(CredentialsLogin(pwHasher.hash(pw), isActive = false, token, account))
        mailerService.sendConfirmation(account, resolver.activationUrl(token).toString)
      }

      em.merge(account)
    }

    get(created.uuid, requireActive = false)
  }

  def update(account: Account): Account = {
    val updated = transaction { (em, tx) =>
      val accountCecked = get(account.uuid)

      // prevent update of logins (this may not be necessary)
      account.logins = accountCecked.logins
      account.mtime = new DateTime()
      em.merge(account)
    }

    get(updated.uuid)
  }

  def delete(account: Account): Unit = {
    transaction { (em, tx) =>
      val accountCecked = em.find(classOf[Account], account.uuid)

      accountCecked.logins.foreach(em.remove(_))
      em.remove(accountCecked)
    }
  }
}

class LoginStore extends IdentityService[Login] {

  override def retrieve(loginInfo: LoginInfo): Future[Option[Login]] = {

    val login = query { em =>
      val queryStr =
        """SELECT DISTINCT l FROM CredentialsLogin l
           LEFT JOIN FETCH l.account a
           WHERE LOWER(a.mail) = LOWER(:email) AND l.isActive = true"""

      val query: TypedQuery[CredentialsLogin] = em.createQuery(queryStr, classOf[CredentialsLogin])
      query.setParameter("email", loginInfo.providerKey)

      Try(query.getSingleResult)
    } match {
      case Success(l) => Some(l)
      case Failure(e) => None
    }

    Future.successful(login)
  }

}


class CredentialsStore extends DelegableAuthInfoDAO[PasswordInfo] {

  def update(nloginInfo: LoginInfo, ologinInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] = {
    Try {
      transaction { (em, tx) =>
        val queryStr =
          """SELECT DISTINCT l FROM CredentialsLogin l
             LEFT JOIN FETCH l.account a
             WHERE LOWER(a.mail) = LOWER(:email)"""

        val query: TypedQuery[CredentialsLogin] = em.createQuery(queryStr, classOf[CredentialsLogin])
        query.setParameter("email", ologinInfo.providerKey)

        val credentials = query.getSingleResult

        credentials.hasher = authInfo.hasher
        credentials.password = authInfo.password
        credentials.salt = authInfo.salt match {
          case Some(salt) => salt
          case _ => null
        }
        credentials.account.mail=nloginInfo.providerKey
        em.merge(credentials)
      }
    } match {
      case Success(login) => Future.successful(PasswordInfo(login.hasher, login.password, Option(login.salt)))
      case Failure(e) => Future.failed(e)
    }
  }
    override def save(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] = {
    Try {
      transaction { (em, tx) =>
        val queryStr =
          """SELECT DISTINCT l FROM CredentialsLogin l
             LEFT JOIN FETCH l.account a
             WHERE LOWER(a.mail) = LOWER(:email)"""

        val query: TypedQuery[CredentialsLogin] = em.createQuery(queryStr, classOf[CredentialsLogin])
        query.setParameter("email", loginInfo.providerKey)

        val credentials = query.getSingleResult

        credentials.hasher = authInfo.hasher
        credentials.password = authInfo.password
        credentials.salt = authInfo.salt match {
          case Some(salt) => salt
          case _ => null
        }
        
        em.merge(credentials)
      }
    } match {
      case Success(login) => Future.successful(PasswordInfo(login.hasher, login.password, Option(login.salt)))
      case Failure(e) => Future.failed(e)
    }
  }

  override def find(loginInfo: LoginInfo): Future[Option[PasswordInfo]] = {
    Try {
      query { em =>
        val queryStr =
          """SELECT DISTINCT l FROM CredentialsLogin l
             LEFT JOIN FETCH l.account a
             WHERE LOWER(a.mail) = LOWER(:email) AND l.isActive = true"""

        val query: TypedQuery[CredentialsLogin] = em.createQuery(queryStr, classOf[CredentialsLogin])
        query.setParameter("email", loginInfo.providerKey)

        query.getSingleResult
      }
    } match {
      case Success(login) => Future.successful(Some(PasswordInfo(login.hasher, login.password, Option(login.salt))))
      case Failure(e) => e match {
        case e: NoResultException => Future.successful(None)
        case _ => Future.failed(e)
      }
    }
  }

  def activate(token: String): Future[PasswordInfo] = {
    Try {
      transaction { (em, tx) =>
        val queryStr =
          """SELECT DISTINCT l FROM CredentialsLogin l
             LEFT JOIN FETCH l.account a
             WHERE l.token = :token"""

        val query: TypedQuery[CredentialsLogin] = em.createQuery(queryStr, classOf[CredentialsLogin])
        query.setParameter("token", token)

        val login = query.getSingleResult

        login.isActive = true
        login.token = null

        em.merge(login)
      }
    } match {
      case Success(login) =>
        Future.successful(PasswordInfo(login.hasher, login.password, Option(login.salt)))
      case Failure(e) =>
        Future.failed(e)
    }
  }

}
