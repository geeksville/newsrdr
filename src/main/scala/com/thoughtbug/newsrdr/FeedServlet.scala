package com.thoughtbug.newsrdr

import org.scalatra._
import scalate.ScalateSupport
import scala.slick.session.Database
import com.thoughtbug.newsrdr.models._
import com.thoughtbug.newsrdr.tasks._

// Use H2Driver to connect to an H2 database
import scala.slick.driver.H2Driver.simple._

// Use the implicit threadLocalSession
import Database.threadLocalSession

// JSON-related libraries
import org.json4s.{DefaultFormats, Formats}

// JSON handling support from Scalatra
import org.scalatra.json._

// Swagger support
import org.scalatra.swagger._

class FeedServlet(db: Database, implicit val swagger: Swagger) extends NewsrdrStack
  with NativeJsonSupport with SwaggerSupport with ApiExceptionWrapper with AuthOpenId {

  override protected val applicationName = Some("feeds")
  protected val applicationDescription = "The feeds API. This exposes operations for manipulating the feed list."
    
  // Sets up automatic case class to JSON output serialization
  protected implicit val jsonFormats: Formats = DefaultFormats

  // Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }
  
  val getFeeds = 
    (apiOperation[FeedListApiResult]("getFeeds")
        summary "Shows all feeds"
        notes "Returns the list of all feeds the currently logged-in user is subscribed to."
        parameter queryParam[Option[Integer]]("page").description("The page of results to retrieve."))
        
  get("/", operation(getFeeds)) {
    authenticationRequired(session.getId, db, {
      executeOrReturnError {
        var userId = getUserId(db, session.getId).get
        
        db withSession {
          FeedListApiResult(true, None, 
            (for { 
              uf <- UserFeeds if uf.userId === userId
              f <- NewsFeeds if f.id === uf.feedId
              } yield f).list.map((x) => {
                var feed_posts = for { 
            	    (nfa, ua) <- Query(NewsFeedArticles) leftJoin UserArticles on (_.id === _.articleId)
            	    	if nfa.feedId === x.id.get
                    uf <- UserFeeds if uf.userId === userId && nfa.feedId === uf.feedId} yield (nfa, ua.articleRead.?)
                NewsFeedInfo(
            		  x, 
            		  (for { (fp, fq) <- feed_posts.list if fq.getOrElse(false) == false } yield fp ).length)
              }));
        }
      }
    }, {
      halt(401)
    })
  }
  
  val postFeeds =
    (apiOperation[NewsFeedInfo]("postFeeds")
        summary "Adds a new feed"
        notes "Subscribes the currently logged-in user to the given feed. It will perform the initial fetch of the feed if it doesn't already exist, so fetching the unread posts for this feed would be a good idea."
        parameter queryParam[String]("url").description("The URL to the given feed to add."))
   
  post("/", operation(postFeeds)) {
    authenticationRequired(session.getId, db, {
	    val url = params.getOrElse("url", halt(422))
	    var userId = getUserId(db, session.getId).get
	    
	    // TODO: handle possible exceptions and output error data.
	    // We probably also want to return validation error info above.
	    db withTransaction {
	      // Grab feed from database, creating if it doesn't already exist.
	      var feedQuery = for { f <- NewsFeeds if f.feedUrl === url } yield f
	      var feed = feedQuery.firstOption match {
	        case Some(f) => f
	        case None => {
	          var fetchJob = new RssFetchJob
	          var f = fetchJob.fetch(url)
	          
	          // Schedule periodic feed updates
	          BackgroundJobManager.scheduleFeedJob(url)
	          
	          f
	        }
	      }
	      
	      // Add subscription at the user level.
	      var userFeed = for { uf <- UserFeeds if uf.userId === userId && uf.feedId === feed.id } yield uf
	      userFeed.firstOption match {
	        case Some(uf) => ()
	        case None => {
	          UserFeeds.insert(UserFeed(None, userId, feed.id.get))
	          ()
	        }
	      }
	      
	      var feed_posts = for { 
	      	(nfa, ua) <- Query(NewsFeedArticles) leftJoin UserArticles on (_.id === _.articleId)
	        if nfa.feedId === feed.id.get
	        uf <- UserFeeds if uf.userId === userId && nfa.feedId === uf.feedId} yield (nfa, ua.articleRead.?)
	      NewsFeedInfo(
	    		  feed, 
	    		  (for { (fp, fq) <- feed_posts.list if fq.getOrElse(false) == false } yield fp ).length)
	    }
    }, {
      halt(401)
    })
  }
  
  val deleteFeeds =
    (apiOperation[Unit]("deleteFeeds")
        summary "Unsubscribes from a feed"
        notes "Unsubscribes the currently logged in user from the given feed."
        parameter pathParam[Int]("id").description("The ID of the feed to unsubscribe from."))
        
  delete("/:id", operation(deleteFeeds)) {
    authenticationRequired(session.getId, db, {
	    val id = params.getOrElse("id", halt(422))
	    var userId = getUserId(db, session.getId).get
	    
	    // TODO: handle possible exceptions and output error data.
	    // We probably also want to return validation error info above.
	    db withTransaction {
	      // Remove subscription at the user level.
	      var userFeed = for { uf <- UserFeeds if uf.userId === userId && uf.feedId === Integer.parseInt(id) } yield uf
	      userFeed.delete
	    }
	    
	    NoDataApiResult(true, None)
    }, {
      halt(401)
    })
  }
  
  val getPostsForFeed =
    (apiOperation[List[NewsFeedArticleInfo]]("getPostsForFeed")
        summary "Retrieves posts for a feed"
        notes "Retrieves posts for the given feed ID."
        parameter pathParam[Int]("id").description("The ID of the feed to operate upon.")
        parameter queryParam[Option[Boolean]]("unread_only").description("Whether to only retrieve unread posts.")
        parameter queryParam[Option[Integer]]("page").description("The page of results to retrieve."))
        
  get("/:id/posts", operation(getPostsForFeed)) {
      authenticationRequired(session.getId, db, {
	      var id = params.getOrElse("id", halt(422))
	      var offset = Integer.parseInt(params.getOrElse("page", "0")) * Constants.ITEMS_PER_PAGE
	      var userId = getUserId(db, session.getId).get
	      
	      db withSession {
	        var feed_posts = for { 
	            (nfa, ua) <- Query(NewsFeedArticles).sortBy(_.pubDate.desc) leftJoin UserArticles on (_.id === _.articleId)
	            	if nfa.feedId === Integer.parseInt(id)
	            uf <- UserFeeds if uf.userId === userId && nfa.feedId === uf.feedId} yield (nfa, ua.articleRead.?)
	      
	        params.get("unread_only") match {
	          case Some(unread_only_string) if unread_only_string.toLowerCase() == "true" => {
	            (for { (p, q) <- feed_posts.list if q.getOrElse(false) == false } yield NewsFeedArticleInfo(p, true)).drop(offset).take(Constants.ITEMS_PER_PAGE)
	          }
	          case _ => (for { (fp, fq) <- feed_posts.list } yield NewsFeedArticleInfo(fp, fq.getOrElse(false) == false)).drop(offset).take(Constants.ITEMS_PER_PAGE)
	        }
	      }
      }, {
      halt(401)
    })
  }
  
  val markReadCommand =
    (apiOperation[Unit]("markRead")
        summary "Marks the given post as read."
        notes "Marks the given post as read."
        parameter pathParam[Int]("id").description("The ID of the feed.")
        parameter pathParam[Int]("pid").description("The ID of the post."))
        
  delete("/:id/posts/:pid", operation(markReadCommand)) {
    authenticationRequired(session.getId, db, {
	    var id = params.getOrElse("id", halt(422))
	    var pid = params.getOrElse("pid", halt(422))
	    var userId = getUserId(db, session.getId).get
	    
	    db withTransaction {
	      var my_feed = for { uf <- UserFeeds if uf.feedId === Integer.parseInt(id) && uf.userId === userId } yield uf
	      my_feed.firstOption match {
	        case Some(_) => {
	          var feed_posts = for {
	            (nfa, ua) <- NewsFeedArticles leftJoin UserArticles on (_.id === _.articleId)
	            	if nfa.feedId === Integer.parseInt(id) && ua.articleId === Integer.parseInt(pid)
	            uf <- UserFeeds if uf.userId === userId && nfa.feedId === uf.feedId} yield ua
	          feed_posts.firstOption match {
	              case Some(x) => feed_posts.update(UserArticle(x.id, x.userId, x.articleId, true))
	              case None => UserArticles.insert(UserArticle(None, userId, Integer.parseInt(pid), true))
	          }
	        }
	        case _ => halt(404)
	      }
	    }
	    
	    NoDataApiResult(true, None)
    }, {
      halt(401)
    })
  }
  
  val markUnreadCommand =
    (apiOperation[Unit]("markUnread")
        summary "Marks the given post as unread."
        notes "Marks the given post as unread."
        parameter pathParam[Int]("id").description("The ID of the feed.")
        parameter pathParam[Int]("pid").description("The ID of the post."))
        
  put("/:id/posts/:pid", operation(markUnreadCommand)) {
    authenticationRequired(session.getId, db, {
	    var id = params.getOrElse("id", halt(422))
	    var pid = params.getOrElse("pid", halt(422))
	    var userId = getUserId(db, session.getId).get

	    db withTransaction {
	      var my_feed = for { uf <- UserFeeds if uf.feedId === Integer.parseInt(id) && uf.userId === userId } yield uf
	      my_feed.firstOption match {
	        case Some(_) => {
	          var feed_posts = for {
	            (nfa, ua) <- NewsFeedArticles leftJoin UserArticles on (_.id === _.articleId)
	            	if nfa.feedId === Integer.parseInt(id) && ua.articleId === Integer.parseInt(pid)
	            uf <- UserFeeds if uf.userId === userId && nfa.feedId === uf.feedId} yield ua
	          feed_posts.firstOption match {
	              case Some(x) => feed_posts.update(UserArticle(x.id, x.userId, x.articleId, false))
	              case None => UserArticles.insert(UserArticle(None, userId, Integer.parseInt(pid), false))
	          }
	        }
	        case _ => halt(404)
	      }
	    }
	    
	    NoDataApiResult(true, None)
    }, {
      halt(401)
    })
  }
}