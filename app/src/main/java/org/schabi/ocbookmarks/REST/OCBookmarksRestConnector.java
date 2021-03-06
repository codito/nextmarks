package org.schabi.ocbookmarks.REST;

import android.content.Context;
import android.util.Log;
import com.owncloud.android.lib.common.network.AdvancedSslSocketFactory;
import com.owncloud.android.lib.common.network.NetworkUtils;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.ocbookmarks.LoginData;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Created by the-scrabi on 14.05.17.
 */


public class OCBookmarksRestConnector {
    private static final String TAG = OCBookmarksRestConnector.class.getCanonicalName();
    private Context context;
    private String apiRootUrl;
    private String usr;
    private String pwd;

    private static final int TIME_OUT = 10000; // in milliseconds

    public OCBookmarksRestConnector(String owncloudRootUrl, String user, String password) {
        apiRootUrl = owncloudRootUrl + "/index.php/apps/bookmarks/public/rest/v2";
        usr = user;
        pwd = password;
    }

    public OCBookmarksRestConnector(LoginData loginData, Context applicationContext) {
        this(loginData.url, loginData.user, loginData.password);

        this.context = applicationContext;
    }

    public JSONObject send(String method, String relativeUrl) throws RequestException {
        OkHttpClient client = null;
        Request request = null;
        URL url = null;
        try {
            url = new URL(apiRootUrl + relativeUrl);

            HostnameVerifier hostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    Log.d(TAG, "Trust Host :" + hostname);
                    return true;
                }
            };

            AdvancedSslSocketFactory sslFactory = NetworkUtils.getAdvancedSslSocketFactory(context);
            client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslFactory.getSslContext().getSocketFactory())
//                    .sslSocketFactory(sslContext.getSocketFactory())
                    .hostnameVerifier(hostnameVerifier)
//                    .hostnameVerifier(sslFactory.getHostNameVerifier())
                    .build();

            request = new Request.Builder()
                    .url(url)
                    .method(method, null)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Basic " + new String(Base64.encodeBase64((usr + ":" + pwd).getBytes())))
                    .build();
        } catch (Exception e) {
            throw new RequestException("Could not setup request", e);
        }

        String responseBody = null;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            Headers responseHeaders = response.headers();

            responseBody = response.body().string();
        } catch (Exception e) {
            if(e.getMessage().contains("500")) {
                throw new PermissionException(e);
            }
            throw new RequestException(e);
        }

        return parseJson(method, url.toString(), responseBody);
    }

    private JSONObject parseJson(String method, String url, String response) throws RequestException {

        JSONObject data = null;
        if(method.equals("GET") && url.endsWith("/tag")) {
            // we have to handle GET /tag different:
            // https://github.com/nextcloud/bookmarks#list-all-tags
            JSONArray array = null;
            try {
                array = new JSONArray(response);
                data = new JSONObject();
                data.put("data", array);
            } catch (JSONException je) {
                throw new RequestException("Parsing error, maybe owncloud does not support bookmark api", je);
            }
            return data;
        } else if(method == "PUT") {
            try {
                data = new JSONObject(response);
                return data.getJSONObject("item");
            } catch (JSONException je) {
                throw new RequestException("Parsing error, maybe owncloud does not support bookmark api", je);
            }
        } else {

            try {
                data = new JSONObject(response);
            } catch (JSONException je) {
                throw new RequestException("Parsing error, maybe owncloud does not support bookmark api", je);
            }

            try {
                if (!data.getString("status").equals("success")) {
                    throw new RequestException("Error bad request: " + url);
                }
            } catch (JSONException e) {
                throw new RequestException("Error bad request: " + url, e);
            }
            return data;
        }
    }

    // +++++++++++++++++
    // +   bookmarks   +
    // +++++++++++++++++

    public JSONArray getRawBookmarks() throws RequestException {
        try {
            return send("GET", "/bookmark?page=-1")
                    .getJSONArray("data");
        } catch (JSONException e) {
            throw new RequestException("Could not parse data", e);
        }
    }

    public Bookmark[] getFromRawJson(JSONArray data) throws RequestException {
        try {
            Bookmark[] bookmarks = new Bookmark[data.length()];
            for (int i = 0; i < data.length(); i++) {
                JSONObject bookmark = data.getJSONObject(i);
                bookmarks[i] = getBookmarkFromJsonO(bookmark);
            }
            return bookmarks;
        } catch (JSONException e) {
            throw new RequestException("Could not parse data", e);
        }
    }

    public Bookmark[] getBookmarks() throws RequestException {
        JSONArray data = getRawBookmarks();
        return getFromRawJson(data);
    }


    private Bookmark getBookmarkFromJsonO(JSONObject jBookmark) throws RequestException {

        String[] tags;
        try {
            JSONArray jTags = jBookmark.getJSONArray("tags");
            tags = new String[jTags.length()];
            for (int j = 0; j < tags.length; j++) {
                tags[j] = jTags.getString(j);
            }
        } catch (JSONException je) {
            throw new RequestException("Could not parse array", je);
        }

        //another api error we need to fix
        if(tags.length == 1 && tags[0].isEmpty()) {
            tags = new String[0];
        }

        try {
            return Bookmark.emptyInstance()
                    .setId(jBookmark.getInt("id"))
                    .setUrl(jBookmark.getString("url"))
                    .setTitle(jBookmark.getString("title"))
                    .setUserId(jBookmark.getString("user_id"))
                    .setDescription(jBookmark.getString("description"))
                    .setPublic(jBookmark.getInt("public") != 0)
                    .setAdded(new Date(jBookmark.getLong("added") * 1000))
                    .setLastModified(new Date(jBookmark.getLong("lastmodified") * 1000))
                    .setClickcount(jBookmark.getInt("clickcount"))
                    .setTags(tags);
        } catch (JSONException je) {
            throw new RequestException("Could not gather all data", je);
        }
    }

    private String createBookmarkParameter(Bookmark bookmark) {
        if(!bookmark.getTitle().isEmpty() && !bookmark.getUrl().startsWith("http")) {
            //tittle can only be set if the sheme is given
            //this is a bug we need to fix
            bookmark.setUrl("http://" + bookmark.getUrl());
        }

        String url = "?url=" + URLEncoder.encode(bookmark.getUrl());

        if(!bookmark.getTitle().isEmpty()) {
            url += "&title=" + URLEncoder.encode(bookmark.getTitle());
        }
        if(!bookmark.getDescription().isEmpty()) {
            url += "&description=" + URLEncoder.encode(bookmark.getDescription());
        }
        if(bookmark.isPublic()) {
            url += "&is_public=1";
        }

        for(String tag : bookmark.getTags()) {
            url += "&" + URLEncoder.encode("item[tags][]") + "=" + URLEncoder.encode(tag);
        }

        return url;
    }

    public Bookmark addBookmark(Bookmark bookmark) throws RequestException {
        try {
            if (bookmark.getId() == -1) {
                String url = "/bookmark" + createBookmarkParameter(bookmark);

                JSONObject replay = send("POST", url);
                return getBookmarkFromJsonO(replay.getJSONObject("item"));
            } else {
                throw new RequestException("Bookmark id is set. Maybe this bookmark already exist: id=" + bookmark.getId());
            }
        } catch (JSONException je) {
            throw new RequestException("Could not parse reply", je);
        }
    }

    public void deleteBookmark(Bookmark bookmark) throws RequestException {
        if(bookmark.getId() < 0) {
            return;
        }
        send("DELETE", "/bookmark/" + Integer.toString(bookmark.getId()));
    }

    public Bookmark editBookmark(Bookmark bookmark) throws RequestException {
        return editBookmark(bookmark, bookmark.getId());
    }

    public Bookmark editBookmark(Bookmark bookmark, int newRecordId) throws RequestException {
        if(bookmark.getId() < 0) {
            throw new RequestException("Bookmark has no valid id. Maybe you want to add a bookmark? id="
                    + Integer.toString((bookmark.getId())));
        }
        if(bookmark.getUrl().isEmpty()) {
            throw new RequestException("Bookmark has no url. Maybe you want to add a bookmark?");
        }
        String url = "/bookmark/" + Integer.toString(bookmark.getId()) + createBookmarkParameter(bookmark);
        url += "&record_id=" + Integer.toString(newRecordId);

        return getBookmarkFromJsonO(send("PUT", url));
    }

    // ++++++++++++++++++
    // +      tags      +
    // ++++++++++++++++++

    public String[] getTags() throws RequestException {
        try {
            JSONArray data = send("GET", "/tag").getJSONArray("data");

            String[] tags = new String[data.length()];
            for (int i = 0; i < tags.length; i++) {
                tags[i] = data.getString(i);
            }

            return tags;
        } catch (JSONException je) {
            throw new RequestException("Could not get all tags", je);
        }
    }

    public void deleteTag(String tag) throws RequestException {
        send("DELETE", "/tag?old_name=" + URLEncoder.encode(tag));
    }

    public void renameTag(String oldName, String newName) throws RequestException {
        send("POST", "/tag?old_name=" + URLEncoder.encode(oldName)
                + "&new_name=" + URLEncoder.encode(newName));
    }

    private static class MyTrustManager implements X509TrustManager
    {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException
        {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException
        {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }
    }
}

