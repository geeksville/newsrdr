(function(){

NewsArticleModel = Backbone.Model.extend({
	onUnreadStatusChanged: function() {
		// We cannot rely on normal Backbone.sync() to update unread status
		// due to the unusual REST API calls needed. Perform the jQuery AJAX
		// call by hand here.
		var self = this;
		var httpType = "PUT";
		if (this.get("unread") == false)
		{
			httpType = "DELETE";
		}
		$.ajax("/feeds/" + this.get("article").feedId + "/posts/" + this.get("article").id, {
			type: httpType,
			error: function(x, y, z) { x.type = httpType; AppController.globalAjaxErrorHandler(x, y, z); }
		}).done(function(data) {
			if (httpType == "DELETE")
			{
				self.get("feedObj").subtractUnread();
			}
			else
			{
				self.get("feedObj").addUnread();
			}
		});
	},
	
	initialize: function() {
		this.on("change:unread", this.onUnreadStatusChanged);
	},
	
	markRead: function() {
		this.set("unread", false);
	},
	
	markUnead: function() {
		this.set("unread", true);
	}
});

}).call(this);