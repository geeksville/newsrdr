package us.newsrdr.servlet

import org.scalatra._
import scalate.ScalateSupport
import org.fusesource.scalate.{ TemplateEngine, Binding }
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import javax.servlet.http.HttpServletRequest
import collection.mutable

trait NewsrdrStack extends ScalatraServlet with GZipSupport with ScalateSupport {

  /* wire up the precompiled templates */
  override protected def defaultTemplatePath: List[String] = List("/WEB-INF/templates/views")
  override protected def createTemplateEngine(config: ConfigT) = {
    val engine = super.createTemplateEngine(config)
    engine.layoutStrategy = new DefaultLayoutStrategy(engine,
      TemplateEngine.templateTypes.map("/WEB-INF/templates/layouts/default." + _): _*)
    engine.packagePrefix = "templates"
    val environment = sys.props.get(org.scalatra.EnvironmentKey).get
    if (environment.equals("production"))
    {
      engine.allowReload = false
    }
    engine
  }
  /* end wiring up the precompiled templates */
  
  override protected def templateAttributes(implicit request: HttpServletRequest): mutable.Map[String, Any] = {
    super.templateAttributes ++ mutable.Map.empty // Add extra attributes here, they need bindings in the build file
  }
  
  notFound {
    // remove content type in case it was set through an action
    contentType = null
    // Try to render a ScalateTemplate if no route matched
    serveStaticResource() getOrElse { 
      contentType = "text/html"
      response.setStatus(404)
      ssp("/404", "title" -> "not found") 
    }
  }
}
