package jp.nisi24.si.twitteremailgae;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import twitter4j.DirectMessage;
import twitter4j.Paging;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.http.AccessToken;
import twitter4j.http.RequestToken;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.mail.MailServiceFactory;
import com.google.appengine.api.mail.MailService.Message;
import com.google.appengine.api.memcache.Expiration;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;

@SuppressWarnings("serial")
public class TwitterEmailGae extends HttpServlet {
	// 設定情報
	private static final String SYSTEM_ADMIN_MAIL = "";
	private static final String YOUR_MAIL = "";
	private static final String CONSUMER_KEY = "";
	private static final String CONSUMER_SECRET = "";

	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		
		String path = request.getRequestURI().substring(request.getContextPath().length());
		
		DatastoreService dataService = DatastoreServiceFactory.getDatastoreService();
		MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
		try {
			Twitter twitter = new TwitterFactory().getOAuthAuthorizedInstance(CONSUMER_KEY, CONSUMER_SECRET);
			// OAuth認証コールバック
			if (path.equals("/oauth_recieve")) {
				RequestToken requestToken = (RequestToken) memcacheService.get("oauth_request_token");
				if (requestToken == null) {
					response.setContentType("text/plain");
					response.setCharacterEncoding("UTF-8");
					response.getWriter().println("多分timeout");
					return;
				}
				AccessToken accessToken = twitter.getOAuthAccessToken(requestToken);
				Entity entity = new Entity("twitter", "oauth_access_token");
				entity.setProperty("token", accessToken.getToken());
				entity.setProperty("tokenSecret", accessToken.getTokenSecret());
				dataService.put(entity);
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().println("OAuth 認証を登録しました。");
				return;
			}
			
			// OAuth認証
			AccessToken accessToken = null;
			try {
				Entity entity = dataService.get(KeyFactory.createKey("twitter", "oauth_access_token"));
				accessToken = new AccessToken((String) entity.getProperty("token"), (String) entity.getProperty("tokenSecret"));
			} catch (EntityNotFoundException e) {}
			if (accessToken == null) {
				// 認証済みでない場合は、リクエストトークンを発行してキャッシュに保持・リダイレクト
				RequestToken requestToken = twitter.getOAuthRequestToken();
				memcacheService.put("oauth_request_token", requestToken, Expiration.byDeltaSeconds(600)); // 10分有効
				response.sendRedirect(requestToken.getAuthorizationURL());  
				return;
			}
			twitter.setOAuthAccessToken(accessToken);
			
			// 以下アプリ
			StringBuilder text = new StringBuilder();
			if (path.equals("/clear")) {
				Entity entity = new Entity("twitter", "timeline");
				entity.setProperty("lastId", -1);
				dataService.put(entity);
				entity = new Entity("twitter", "reply");
				entity.setProperty("lastId", -1);
				dataService.put(entity);
				entity = new Entity("twitter", "direct");
				entity.setProperty("lastId", -1);
				dataService.put(entity);
			}
		
			if (path.equals("/tl")) {
				// 保存された最新のIDを取得
				long lastId = -1;
				try {
					Object id = dataService.get(KeyFactory.createKey("twitter", "timeline")).getProperty("lastId");
					lastId = (id == null ? lastId : ((Long) id).longValue());
				} catch (EntityNotFoundException e) {}
				ResponseList<Status> timeline = twitter.getHomeTimeline();
				for (Status status : timeline) {
					if (lastId == status.getId()) { break; }
					text.append("" + status.getUser().getScreenName() + ":" + status.getText() + "\n");
				}
				if (!"true".equals(request.getParameter("not_save"))) {
					// 最新のIDを保存
					long theLastId = timeline.get(0).getId();
					Entity entity = new Entity("twitter", "timeline");
					entity.setProperty("lastId", theLastId);
					dataService.put(entity);
				}
			} else if (path.equals("/reply")) {
				// 保存された最新のIDを取得
				long lastId = -1;
				try {
					Object id = dataService.get(KeyFactory.createKey("twitter", "reply")).getProperty("lastId");
					lastId = (id == null ? lastId : ((Long) id).longValue());
				} catch (EntityNotFoundException e) {}
				
				ResponseList<Status> mentions = twitter.getMentions(new Paging(1, 8));
				for (Status status : mentions) {
					if (lastId == status.getId()) {
						break;
					}
					text.append("" + status.getUser().getScreenName() + ":" + status.getText() + "\n");
					Status replyTo = null;
					twitter.showStatus(status.getInReplyToStatusId());
					while (replyTo != null) {
						text.append("＞\n");
						text.append("" + replyTo.getUser().getScreenName() + ":" + replyTo.getText() + "\n");
						twitter.showStatus(replyTo.getInReplyToStatusId());
					}
				}
				
				if (!"true".equals(request.getParameter("not_save"))) {
					// 最新のIDを保存
					long theLastId = mentions.get(0).getId();
					Entity entity = new Entity("twitter", "reply");
					entity.setProperty("lastId", theLastId);
					dataService.put(entity);
				}
			} else if (path.equals("/direct")) {
				// 保存された最新のIDを取得
				long lastId = -1;
				try {
					Object id = dataService.get(KeyFactory.createKey("twitter", "direct")).getProperty("lastId");
					lastId = (id == null ? lastId : ((Long) id).longValue());
				} catch (EntityNotFoundException e) {}
				
				ResponseList<DirectMessage> messages = twitter.getDirectMessages(new Paging(1, 8));
				for (DirectMessage message : messages) {
					if (lastId == message.getId()) {
						break;
					}
					text.append(message.getSender().getScreenName() + ":" + message.getText() + "\n");
				}
				
				if (!"true".equals(request.getParameter("not_save"))) {
					// 最新のIDを保存
					long theLastId = messages.get(0).getId();
					Entity entity = new Entity("twitter", "direct");
					entity.setProperty("lastId", theLastId);
					dataService.put(entity);
				}
			}
			if ("true".equals(request.getParameter("ispc"))) {
				response.setContentType("text/plain");
//				response.setContentType("text/html");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().println(text.toString());
			} else {
				if (text.length() > 0) {
					Message message = new Message();
					message.setSubject("Twitter " + path);
					message.setTo(YOUR_MAIL);
					message.setSender(SYSTEM_ADMIN_MAIL);
					message.setTextBody(text.toString());
					MailServiceFactory.getMailService().send(message);
				}
			}
		} catch (TwitterException e) {
			response.setContentType("text/plain");
			response.setCharacterEncoding("UTF-8");
			e.printStackTrace(response.getWriter());
		}
	}
}
