package org.schabi.ocbookmarks;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.webkit.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.owncloud.android.lib.common.network.NetworkUtils;
import org.schabi.ocbookmarks.REST.OCBookmarksRestConnector;
import org.schabi.ocbookmarks.REST.RequestException;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LoginActivity extends AppCompatActivity implements CertErrorDialogFragment.Listener {

    // reply info
    private static final int OK = 0;
    private static final int CONNECTION_FAIL = 1;
    private static final int HOST_NOT_FOUND= 2;
    private static final int FILE_NOT_FOUND = 3;
    private static final int TIME_OUT = 4;

    private LoginData loginData = new LoginData();

    private EditText urlInput;
    private Button connectButton;
    private ProgressBar progressBar;
    private TextView errorView;

    private SharedPreferences sharedPrefs;

    private TestLoginTask testLoginTask;
    private WebView webView;

    private Activity getActivity() {
        return this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_activity);

        getSupportActionBar().setElevation(0);
        getSupportActionBar().setTitle(getString(R.string.oc_bookmark_login));
        urlInput = (EditText) findViewById(R.id.urlInput);
        connectButton = (Button) findViewById(R.id.connectButton);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        errorView = (TextView) findViewById(R.id.loginErrorView);
        webView = new WebView(getBaseContext());
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(getWebLoginUserAgent());
        webView.getSettings().setSaveFormData(false);
        webView.getSettings().setSavePassword(false);
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH);
        webView.clearCache(true);
        webView.clearFormData();
        webView.clearHistory();
        WebView.clearClientCertPreferences(null);

        CookieSyncManager.createInstance(getActivity());
        android.webkit.CookieManager.getInstance().removeAllCookies(null);

        Map<String, String> headers = new HashMap<>();
        headers.put("OCS-APIRequest", "true");
        webView.setWebViewClient(new LoginWebViewClient(webView, loginData));

        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        webView.setVisibility(View.VISIBLE);

        sharedPrefs = getSharedPreferences(getPackageName(), Context.MODE_PRIVATE);
        urlInput.setText(sharedPrefs.getString(getString(R.string.login_url), ""));

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loginData.url = fixUrl(urlInput.getText().toString());
                urlInput.setText(loginData.url);

                webView.loadUrl(loginData.url + "/index.php/login/flow", headers);
                setContentView(webView);
            }
        });
    }

    private String getWebLoginUserAgent() {
        return Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.getDefault()) +
                Build.MANUFACTURER.substring(1).toLowerCase(Locale.getDefault()) + " " + Build.MODEL + " (nextmarks)";
    }

    private String fixUrl(String rawUrl) {
        if(!rawUrl.startsWith("http")) {
            rawUrl = "https://" + rawUrl;
        }
        if(rawUrl.endsWith("/")) {
            rawUrl = rawUrl.substring(0, rawUrl.length()-1);
        }
        return rawUrl;
    }

    private void storeLogin(LoginData loginData) {
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putString(getString(R.string.login_url), loginData.url);
        editor.putString(getString(R.string.login_user), loginData.user);
        editor.putString(getString(R.string.login_pwd), loginData.password);
        editor.apply();
    }

    private void deleteFiles() {
        // delete files from a previous login
        File homeDir = getApplicationContext().getFilesDir();
        for(File file : homeDir.listFiles()) {
            if(file.toString().contains(".png") ||
                    file.toString().contains(".noicon") ||
                    file.toString().contains(".json")) {
                file.delete();
            }
        }
    }

    @Override
    public void onCertErrorClicked(int position) {
        // empty
    }

    /**
     * Intercepts the web authentication flow and retrieves app credentials.
     * See https://docs.nextcloud.com/server/16/developer_manual/client_apis/LoginFlow/index.html
     */
    private class LoginWebViewClient extends WebViewClient {
        private static final String LOGIN_URL_DATA_KEY_VALUE_SEPARATOR = ":";
        private final WebView webView;
        private final LoginData loginData;
        private boolean basePageLoaded;
        private int loginStep = 0;
        private String username = "";
        private String password = "";

        public LoginWebViewClient(WebView webView, LoginData loginData) {
            this.webView = webView;
            this.loginData = loginData;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("nc://login")) {
                parseLoginData("nc://login/", url);

                webView.setVisibility(View.GONE);
                testLoginTask = new TestLoginTask();
                testLoginTask.execute(loginData);
                progressBar.setVisibility(View.VISIBLE);
                connectButton.setVisibility(View.INVISIBLE);

                return true;
            }
            return false;
        }

        private LoginData parseLoginData(String prefix, String dataString) {
            if (dataString.length() < prefix.length()) {
                return null;
            }

            // format is xxx://login/server:xxx&user:xxx&password:xxx
            String data = dataString.substring(prefix.length());

            String[] values = data.split("&");

            if (values.length != 3) {
                return null;
            }

            for (String value : values) {
                if (value.startsWith("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                    loginData.user = (URLDecoder.decode(
                            value.substring(("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
                } else if (value.startsWith("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                    loginData.password = (URLDecoder.decode(
                            value.substring(("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
                } else if (value.startsWith("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                    loginData.url = (URLDecoder.decode(
                            value.substring(("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
                } else {
                    return null;
                }
            }

            if (!TextUtils.isEmpty(loginData.url) && !TextUtils.isEmpty(loginData.user) &&
                    !TextUtils.isEmpty(loginData.password)) {
                return loginData;
            } else {
                return null;
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loginStep++;

            if (!basePageLoaded) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }

                if (webView != null) {
                    webView.setVisibility(View.VISIBLE);
                }
                basePageLoaded = true;
            }

//            username = loginData.user;
//            password = loginData.password;
            if (!TextUtils.isEmpty(username) && webView != null) {
                boolean automatedLoginAttempted = false;
                if (loginStep == 1) {
                    webView.loadUrl("javascript: {document.getElementsByClassName('login')[0].click(); };");
                } else if (!automatedLoginAttempted) {
                    automatedLoginAttempted = true;
                    if (TextUtils.isEmpty(password)) {
                        webView.loadUrl("javascript:var justStore = document.getElementById('user').value = '" + username + "';");
                    } else {
                        webView.loadUrl("javascript: {" +
                                "document.getElementById('user').value = '" + username + "';" +
                                "document.getElementById('password').value = '" + password + "';" +
                                "document.getElementById('submit').click(); };");
                    }
                }
            }

            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            // Not supporting client cert based auth for the moment
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            try {
                SslCertificate sslCertificate = error.getCertificate();
                Field f = sslCertificate.getClass().getDeclaredField("mX509Certificate");
                f.setAccessible(true);
                X509Certificate cert = (X509Certificate) f.get(sslCertificate);

                if (cert == null) {
                    handler.cancel();
                } else {
                    if (!NetworkUtils.isCertInKnownServersStore(cert, getApplicationContext())) {
                        CertErrorDialogFragment.newInstance(3).show(getSupportFragmentManager(), "dialog");

                        NetworkUtils.addCertToKnownServersStore(cert, getApplicationContext());
                    }

                    handler.proceed();
                }
            } catch (Exception exception) {
                handler.cancel();
            }
        }

    }

    private class TestLoginTask extends AsyncTask<LoginData, Void, Integer> {
        protected Integer doInBackground(LoginData... loginDatas) {
            LoginData loginData = loginDatas[0];
            OCBookmarksRestConnector connector =
                    new OCBookmarksRestConnector(loginData, getApplicationContext());
            try {
                connector.getBookmarks();
                return OK;
            } catch (RequestException re) {
                if(BuildConfig.DEBUG) {
                    re.printStackTrace();
                }

                if(re.getMessage().contains("FileNotFound")) {
                    return FILE_NOT_FOUND;
                }
                if(re.getMessage().contains("UnknownHost")) {
                    return HOST_NOT_FOUND;
                }
                if(re.getMessage().contains("SocketTimeout")) {
                    return TIME_OUT;
                }
                return CONNECTION_FAIL;
            } catch (Exception e) {
                return CONNECTION_FAIL;
            }
        }

        protected void onPostExecute(Integer result) {
            connectButton.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            switch (result) {
                case OK:
                    storeLogin(loginData);
                    deleteFiles();
                    finish();
                    break;
                case CONNECTION_FAIL:
                    errorView.setText(getString(R.string.connection_failed_login));
                    errorView.setVisibility(View.VISIBLE);
                    break;
                case HOST_NOT_FOUND:
                    errorView.setText(getString(R.string.login_host_not_found));
                    errorView.setVisibility(View.VISIBLE);
                    break;
                case FILE_NOT_FOUND:
                    errorView.setText(getString(R.string.login_failed));
                    errorView.setVisibility(View.VISIBLE);
                    break;
                case TIME_OUT:
                    errorView.setText(getString(R.string.login_timeout));
                    errorView.setVisibility(View.VISIBLE);
                    break;
                default:
                    break;
            }

        }
    }
}
