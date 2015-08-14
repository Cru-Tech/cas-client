/*  Copyright (c) 2000-2004 Yale University. All rights reserved. 
 *  See full notice at end.
 */

package edu.yale.its.tp.cas.client.filter;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import edu.yale.its.tp.cas.util.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.yale.its.tp.cas.client.CASAuthenticationException;
import edu.yale.its.tp.cas.client.CASReceipt;
import edu.yale.its.tp.cas.client.ProxyTicketValidator;
import edu.yale.its.tp.cas.client.Util;

/**
 * <p>
 * Protects web-accessible resources with CAS.
 * </p>
 * 
 * <p>
 * The following filter initialization parameters are declared in
 * <code>web.xml</code>:
 * </p>
 * 
 * <ul>
 * <li><code>edu.yale.its.tp.cas.client.filter.loginUrl</code>: URL to login
 * page on CAS server. (Required)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.validateUrl</code>: URL to
 * validation URL on CAS server. (Required)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.serviceUrl</code>: URL of this
 * service. (Required if <code>serverName</code> is not specified)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.serverName</code>: full hostname
 * with port number (e.g. <code>www.foo.com:8080</code>). Port number isn't
 * required if it is standard (80 for HTTP, 443 for HTTPS). (Required if
 * <code>serviceUrl</code> is not specified)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.authorizedProxy</code>:
 * whitespace-delimited list of valid proxies through which authentication may
 * have proceeded. One one proxy must match. (Optional. If nothing is specified,
 * the filter will only accept service tickets &#150; not proxy tickets.)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.proxyCallbackUrl</code>: URL of
 * local proxy callback listener used to acquire PGT/PGTIOU. (Optional.)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.renew</code>: value of CAS
 * "renew" parameter. Bypasses single sign-on and requires user to provide CAS
 * with his/her credentials again. (Optional. If nothing is specified, this
 * defaults to false.)</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.gateway</code>: value of CAS
 * "gateway" parameter. Redirects initial call through CAS and if the user has
 * logged in, validates the ticket on return. If the user has not logged in,
 * returns to the web application without setting the
 * <code>CAS_FILTER_USER</code> variable. Note that once a redirect through CAS
 * has occurred, the filter will not automatically try again to log the user in.
 * You can then either provide an explicit CAS login link (
 * <code>https://cas-server/cas/login?service=http://your-app</code>) or set up
 * two instances of the filter mapped to different paths. One instance would
 * have gateway=true, the other wouldn't. When you need the user to be logged
 * in, direct him/her to the path of the other filter.</li>
 * <li><code>edu.yale.its.tp.cas.client.filter.wrapRequest</code>: wrap the
 * <code>HttpServletRequest</code> object, overriding the
 * <code>getRemoteUser()</code> method. When set to "true",
 * <code>request.getRemoteUser()</code> will return the username of the
 * currently logged-in CAS user. (Optional. If nothing is specified, this
 * defaults to false.)</li>
 * </ul>
 * 
 * <p>
 * The logged-in username is set in the session attribute defined by the value
 * of <code>CAS_FILTER_USER</code> and may be accessed from within your
 * application either by setting <code>wrapRequest</code> and calling
 * <code>request.getRemoteUser()</code>, or by calling
 * <code>session.getAttribute(CASFilter.CAS_FILTER_USER)</code>.
 * </p>
 * 
 * <p>
 * If <code>proxyCallbackUrl</code> is set, the URL will be passed to CAS upon
 * validation. If the callback URL is valid, it will receive a CAS PGT and a
 * PGTIOU. The PGTIOU will be returned to this filter and will be accessible
 * through the session attribute, <code>CASFilter.CAS_FILTER_PGTIOU</code>. You
 * may then acquire proxy tickets to other services by calling
 * 
 * <code>edu.yale.its.tp.cas.proxy.ProxyTicketReceptor.getProxyTicket(pgtIou, targetService)</code>.
 * 
 * @author Shawn Bayern
 * @author Drew Mazurek
 * @author andrew.petro@yale.edu
 */
public class CASFilter implements Filter
{

    private static Log log = LogFactory.getLog(CASFilter.class);

    // Filter initialization parameters

    /**
     * The name of the filter initialization parameter the value of which should
     * be the https: address of the CAS Login servlet. Optional parameter, but
     * required for successful redirection of unauthenticated requests to
     * authentication.
     */
    public final static String LOGIN_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.loginUrl";

    /**
     * The name of the filter initialization parameter the value of which must
     * be the https: address of the CAS Validate servlet. Must be a CAS 2.0
     * validate servlet (CAS 1.0 non-XML won't suffice). Required parameter.
     */
    public final static String VALIDATE_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.validateUrl";

    /**
     * The name of the filter initialization parameter the value of which must
     * be the address of the service this filter is filtering. The filter will
     * use this as the service parameter for CAS login and validation. Either
     * this parameter or SERVERNAME_INIT_PARAM must be set.
     */
    public final static String SERVICE_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.serviceUrl";

    /**
     * The name of the filter initialization parameter the vlaue of which must
     * be the server name, e.g. www.yale.edu , of the service this filter is
     * filtering. The filter will construct from this name and the request the
     * full service parameter for CAS login and validation.
     */
    public final static String SERVERNAME_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.serverName";

    /**
     * The name of the filter initialization parameter the value of which must
     * be the String that should be sent as the "renew" parameter on the request
     * for login and validation. This should either be "true" or not be set. It
     * is mutually exclusive with GATEWAY.
     */
    public final static String RENEW_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.renew";

    /**
     * The name of the filter initialization parameter the value of which must
     * be a whitespace delimited list of services (ProxyTicketReceptors)
     * authorized to proxy authentication to the service filtered by this
     * Filter. These must be https: URLs. This parameter is optional - not
     * setting it results in no proxy tickets being acceptable.
     */
    public final static String AUTHORIZED_PROXY_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.authorizedProxy";

    /**
     * The name of the filter initialization parameter the value of which must
     * be the https: URL to which CAS should send Proxy Granting Tickets when
     * this filter validates tickets.
     */
    public final static String PROXY_CALLBACK_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.proxyCallbackUrl";

    /**
     * CCCI The name of the filter initialization parameter the value of which
     * is the value the Filter should send for the logout callback url.
     */
    public final static String LOGOUT_CALLBACK_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.logoutCallbackUrl";

    /**
     * The name of the filter initialization parameter the value of which
     * indicates whether this filter should wrap requests to expose the
     * authenticated username.
     */
    public final static String WRAP_REQUESTS_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.wrapRequest";

    /**
     * The name of the filter initialization parameter the value of which is the
     * value the Filter should send for the gateway parameter on the CAS login
     * request.
     */
    public final static String GATEWAY_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.gateway";

    /**
     * CCCI The name of the filter initialization parameter that determines
     * which user attribute is returned from Request.getRemoteUser() by the
     * wrapped request. Either leave unset or set it to an empty string in order
     * to send the username ("netId" in CAS terminology).
     */
    public final static String REMOTE_USER_ATTRIB_INIT_PARAM = "edu.yale.its.tp.cas.client.filter.remoteUserAttrib";

    /**
     * CCCI
     */
    public final static String URL_PATTERN_EXCLUDE_INIT_PARAM = "url-pattern-exclude";

    //CCCI
    public final static String CAS_SERVER_URL_PREFIX = "casServerUrlPrefix";

    // Session attributes used by this filter

    /**
     * <p>
     * Session attribute in which the username is stored.
     * </p>
     */
    public final static String CAS_FILTER_USER = "edu.yale.its.tp.cas.client.filter.user";

    /**
     * Session attribute in which the CASReceipt is stored.
     */
    public final static String CAS_FILTER_RECEIPT = "edu.yale.its.tp.cas.client.filter.receipt";

    /**
     * CCCI Session attribute to indicate that the receipt is fresh
     */
    public final static String CAS_FILTER_RECEIPT_IS_FRESH = "edu.yale.its.tp.cas.client.filter.receiptIsFresh";
    public final static String CAS_FILTER_RECEIPT_IS_FRESH_BEFORE_REDIRECT = "edu.yale.its.tp.cas.client.filter.receiptIsFreshBeforeRedirect";

    /**
     * Session attribute in which internally used gateway attribute is stored.
     */
    private static final String CAS_FILTER_GATEWAYED = "edu.yale.its.tp.cas.client.filter.didGateway";

    //CCCI
    private static final String CAS_FILTER_TICKET_RE_REQUEST_COUNT = "edu.yale.its.tp.cas.client.filter.ticketRerequestCount";

    private static final int INVALID_TICKET_RE_REQUEST_LIMIT = 3;


    private static final String INFINISPAN_LOGOUT_STORE = "infinispanLogoutStore";

    // *********************************************************************
    // Configuration state

    /** Secure URL whereat CAS offers its login service. */
    private String casLogin;
    /** Secure URL whereat CAS offers its CAS 2.0 validate service */
    private String casValidate;
    /** Filtered service URL for use as service parameter to login and validate */
    private String casServiceUrl;
    /**
     * Name of server, for use in assembling service URL for use as service
     * parameter to login and validate.
     */
    private String casServerName;
    /**
     * Secure URL whereto this filter should ask CAS to send Proxy Granting
     * Tickets.
     */
    private String casProxyCallbackUrl;
    /**
     * CCCI Secure URL whereto this filter should ask CAS to send logout
     * commands.
     */
    private String casLogoutCallbackUrl;

    /** True if renew parameter should be set on login and validate */
    private boolean casRenew;

    /**
     * True if this filter should wrap requests to expose authenticated user as
     * getRemoteUser();
     */
    private boolean wrapRequest;

    /** True if this filter should set gateway=true on login redirect */
    private boolean casGateway = false;

    /** CCCI */
    private String remoteUserAttrib = null;

    /** CCCI */
    private List<Pattern> urlPatternExclude = new ArrayList<Pattern>();

    /**
     * CCCI List of ProxyTicketReceptor URLs of services authorized to proxy to
     * the path behind this filter.
     */
    private List authorizedProxies = new ArrayList();

    /**
     * CCCI
     * 
     * List of tickets that are pending logout. The next time the user appears,
     * they will be logged out.
     */
    private LogoutStorage logoutList;

    // *********************************************************************
    // Initialization

    public void init(FilterConfig config) throws ServletException
    {
        System.out.println("Initializing CASFilter");

        initLogoutList();

        casLogin = Configuration.getParameter(config, LOGIN_INIT_PARAM);
        casValidate = Configuration.getParameter(config, VALIDATE_INIT_PARAM);

        String casServerUrlPrefix = Configuration.getParameter(config, CAS_SERVER_URL_PREFIX);
        if (casServerUrlPrefix != null)
        {
            if (casLogin == null)
            {
                casLogin = casServerUrlPrefix + "/login";
            }
            if (casValidate == null)
            {
                casValidate = casServerUrlPrefix + "/serviceValidate";
            }
        }

        casServiceUrl = Configuration.getParameter(config, SERVICE_INIT_PARAM);
        String casAuthorizedProxy = Configuration.getParameter(config, AUTHORIZED_PROXY_INIT_PARAM);
        casRenew = Boolean.valueOf(Configuration.getParameter(config, RENEW_INIT_PARAM));
        casServerName = Configuration.getParameter(config, SERVERNAME_INIT_PARAM);
        casProxyCallbackUrl = Configuration.getParameter(config, PROXY_CALLBACK_INIT_PARAM);
        casLogoutCallbackUrl = Configuration.getParameter(config, LOGOUT_CALLBACK_INIT_PARAM);
        wrapRequest = Boolean.valueOf(Configuration.getParameter(config, WRAP_REQUESTS_INIT_PARAM));
        casGateway = Boolean.valueOf(Configuration.getParameter(config, GATEWAY_INIT_PARAM));
        remoteUserAttrib = Configuration.getParameter(config, REMOTE_USER_ATTRIB_INIT_PARAM);


        if (casGateway && Boolean.valueOf(casRenew)) { throw new ServletException(
            "gateway and renew cannot both be true in filter configuration"); }
        if (casServerName != null && casServiceUrl != null) { throw new ServletException(
            "serverName and serviceUrl cannot both be set: choose one."); }
        // CCCI - commented this out
        // if (casServerName == null && casServiceUrl == null) {
        // throw new
        // ServletException("one of serverName or serviceUrl must be set.");
        // }
        if (casServiceUrl != null)
        {
            if (!(casServiceUrl.startsWith("https://") || (casServiceUrl.startsWith("http://")))) { throw new ServletException(
                "service URL must start with http:// or https://; its current value is [" + casServiceUrl + "]"); }
        }

        if (casValidate == null) { throw new ServletException("validateUrl parameter must be set."); }
        if (!(casValidate.startsWith("https://") || (casValidate.startsWith("http://")))) { throw new ServletException(
            "validateUrl must start with http:// or https://, its current value is [" + casValidate + "]"); }

        if (casAuthorizedProxy != null)
        {

            // parse and remember authorized proxies
            StringTokenizer casProxies = new StringTokenizer(casAuthorizedProxy);
            while (casProxies.hasMoreTokens())
            {
                String anAuthorizedProxy = casProxies.nextToken();
                // if (!anAuthorizedProxy.startsWith("https://")){
                // throw new
                // ServletException("CASFilter initialization parameter for authorized proxies "
                // +
                // "must be a whitespace delimited list of authorized proxies.  "
                // +
                // "Authorized proxies must be secure (https) addresses.  This one wasn't: ["
                // + anAuthorizedProxy + "]");
                // }
                this.authorizedProxies.add(anAuthorizedProxy);
            }
        }

        // CCCI
        String urlPatternExclude = config.getInitParameter(URL_PATTERN_EXCLUDE_INIT_PARAM);
        if (urlPatternExclude != null)
        {
            StringTokenizer excludePatterns = new StringTokenizer(urlPatternExclude);
            // System.out.println("CAS-urlPatternExclude: "+urlPatternExclude);
            while (excludePatterns.hasMoreTokens())
            {
                String anExcludedPattern = excludePatterns.nextToken();
                // System.out.println("CAS-excluding: "+anExcludedPattern);
                this.urlPatternExclude.add(Pattern.compile(anExcludedPattern));
            }
        }

        if (log.isDebugEnabled())
        {
            log.debug(("CASFilter initialized as: [" + toString() + "]"));
        }
    }

    private void initLogoutList()
    {
        // using Object here to avoid runtime dependency on infinispan
        Object store = Configuration.jndiLookup(INFINISPAN_LOGOUT_STORE);
        if (store != null)
        {
            log.info("using infinispan logout storage");
            logoutList = new InfinispanLogoutStorage(store);
        }
        else
        {
            log.info("using non-clustered logout storage");
            logoutList = new ArrayListLogoutStorage();
        }
    }


    // *********************************************************************
    // Filter processing

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain fc) throws ServletException,
            IOException
    {

        if (log.isTraceEnabled())
        {
            log.trace("entering doFilter()");
        }

        // make sure we've got an HTTP request
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse))
        {
            log.error("doFilter() called on a request or response that was not an HttpServletRequest or response.");
            throw new ServletException("CASFilter protects only HTTP resources");
        }

        if (urlPatternExclude != null && urlPatternExclude.size() > 0)
        {
            // System.out.println("CAS-checking for exclusion");
            for (Pattern p : urlPatternExclude)
            {
                Matcher m = p.matcher(((HttpServletRequest) request).getRequestURI());
                // System.out.println("CAS-pattern:"+p.toString());
                if (m.matches())
                {
                    // System.out.println("URL "+((HttpServletRequest)
                    // request).getRequestURI()+" is excluded by pattern: "+p.toString());
                    log.trace("URL " + ((HttpServletRequest) request).getRequestURI() + " is excluded by pattern: "
                            + p.toString());
                    fc.doFilter(wrapIfNecessary(request), response);
                    return;
                }
                // else
                // System.out.println("URL "+((HttpServletRequest)
                // request).getRequestURI()+" is NOT excluded by pattern: "+p.toString());

            }
        }

        // Is this a request for the proxy callback listener? If so, pass
        // it through
        if (casProxyCallbackUrl != null && casProxyCallbackUrl.endsWith(((HttpServletRequest) request).getRequestURI())
                && request.getParameter("pgtId") != null && request.getParameter("pgtIou") != null)
        {
            log.trace("passing through what we hope is CAS's request for proxy ticket receptor.");
            fc.doFilter(wrapIfNecessary(request), response);
            return;
        }

        // CCCI
        // Is this a request for logout? If so, process it
        if (!requestIsPost(request) && request.getParameter("ticket") != null && request.getParameter("ticket").startsWith("-"))
        {
            log.trace("processing logout request.");
            queueForLogout(request, response);
            return;
        }

        HttpSession session = ((HttpServletRequest) request).getSession();

        CASReceipt receipt = (CASReceipt) session.getAttribute(CAS_FILTER_RECEIPT);

        // CCCI
        // if our attribute's already present but queued for logout, then handle
        // the logout
        if (receipt != null && isReceiptQueuedForLogout(receipt))
        {
            handleActualLogout(request, response);
            receipt = null;
        }

        // otherwise, we need to authenticate via CAS
        String ticket = null;
        if(!requestIsPost(request)) ticket = request.getParameter("ticket");

        // CCCI
        // if our attribute's already present and valid, pass through the filter
        // chain
        if (ticket == null && receipt != null && isReceiptAcceptable(receipt))
        {
            log.trace("CAS_FILTER_RECEIPT attribute was present and acceptable - passing  request through filter..");
            if (session.getAttribute(CAS_FILTER_RECEIPT_IS_FRESH_BEFORE_REDIRECT) != null)
            {
                session.removeAttribute(CAS_FILTER_RECEIPT_IS_FRESH_BEFORE_REDIRECT);
            }
            else if (session.getAttribute(CAS_FILTER_RECEIPT_IS_FRESH) != null)
            {
                session.removeAttribute(CAS_FILTER_RECEIPT_IS_FRESH);
            }
            fc.doFilter(wrapIfNecessary(request), response);
            return;
        }

        // no ticket? abort request processing and redirect
        if (ticket == null || ticket.equals(""))
        {
            log.trace("CAS ticket was not present on request.");
            // did we go through the gateway already?
            boolean didGateway = Boolean.valueOf((String) session.getAttribute(CAS_FILTER_GATEWAYED));

            if (casLogin == null)
            {
                // TODO: casLogin should probably be ensured to not be null at
                // filter initialization. -awp9
                log.fatal("casLogin was not set, so filter cannot redirect request for authentication.");
                throw new ServletException("When CASFilter protects pages that do not receive a 'ticket' "
                        + "parameter, it needs a edu.yale.its.tp.cas.client.filter.loginUrl " + "filter parameter");
            }
            if (!didGateway)
            {
                log.trace("Did not previously gateway.  Setting session attribute to true.");
                session.setAttribute(CAS_FILTER_GATEWAYED, "true");
                redirectToCAS((HttpServletRequest) request, (HttpServletResponse) response);
                // abort chain
                return;
            }
            else
            {
                log.trace("Previously gatewayed.");
                // if we should be logged in, make sure validation succeeded
                if (casGateway || session.getAttribute(CAS_FILTER_USER) != null)
                {
                    log.trace("casGateway was true and CAS_FILTER_USER set: passing request along filter chain.");
                    // continue processing the request
                    fc.doFilter(wrapIfNecessary(request), response);
                    return;
                }
                else
                {
                    // unknown state... redirect to CAS
                    session.setAttribute(CAS_FILTER_GATEWAYED, "true");
                    redirectToCAS((HttpServletRequest) request, (HttpServletResponse) response);
                    // abort chain
                    return;
                }
            }
        }

        try
        {
            receipt = getAuthenticatedUser((HttpServletRequest) request);
        }
        catch (CASAuthenticationException e)
        {
            handleTicketValidationFailure(
                (HttpServletRequest) request,
                (HttpServletResponse) response,
                e);
            // abort chain
            return;
        }

        if (!isReceiptAcceptable(receipt)) { throw new ServletException(
            "Authentication was technically successful but rejected as a matter of policy. [" + receipt + "]"); }

        // Store the authenticated user in the session
        if (session != null)
        { // probably unnecessary
            session.setAttribute(CAS_FILTER_USER, receipt.getUserName());
            session.setAttribute(CASFilter.CAS_FILTER_RECEIPT, receipt);
            // CCCI
            session.setAttribute(CAS_FILTER_RECEIPT_IS_FRESH, Boolean.TRUE);
            session.setAttribute(CAS_FILTER_RECEIPT_IS_FRESH_BEFORE_REDIRECT, Boolean.TRUE);
            // don't store extra unnecessary session state
            session.removeAttribute(CAS_FILTER_GATEWAYED);
        }
        if (log.isTraceEnabled())
        {
            log.trace("validated ticket to get authenticated receipt [" + receipt
                    + "], now passing request along filter chain.");
        }

        // continue processing the request

        // CCCI
        // fc.doFilter(wrapIfNecessary(request), response);
        // log.trace("returning from doFilter()");

        String redirectUrl = Util.getService((HttpServletRequest) request, casServerName, false);
        // remove "ticket" parameter - mid-string
        redirectUrl = redirectUrl.replaceAll("ticket=[^&]*&", "");
        // remove "ticket" parameter - end-string
        redirectUrl = redirectUrl.replaceAll("ticket=[^&]*$", "");
        redirectUrl = redirectUrl.replaceAll("&$", "");
        redirectUrl = redirectUrl.replaceAll("\\?$", "");
//        System.out.println("AUTH: Redirecting to self to clean ticket:" + redirectUrl);
        ((HttpServletResponse) response).sendRedirect(redirectUrl);
    }

    private void handleTicketValidationFailure(
        HttpServletRequest request,
        HttpServletResponse response, CASAuthenticationException e)
        throws IOException, ServletException
    {
        if (e.getMessage().contains("INVALID_TICKET"))
        {
            handleInvalidTicket(request, response, e);
        }
        else
        {
            log.error(e);
            throw new ServletException(e);
        }
    }

    private void handleInvalidTicket(
        HttpServletRequest request,
        HttpServletResponse response,
        CASAuthenticationException e) throws ServletException, IOException
    {
        log.warn("Ticket was invalid: ", e);
        int count = countTicketReRequests(request);
        if (count >= INVALID_TICKET_RE_REQUEST_LIMIT)
        {
            throw new ServletException("already re-requested a ticket " + count + " times; giving up. Last failure is chained:", e);
        }
        else
        {
            count++;
            storeIncrementedTicketReRequestCount(request, count);
            log.warn("Requesting a new ticket. (Attempt # " + count + ")");
            redirectToCAS(request, response);
        }
    }

    private int countTicketReRequests(HttpServletRequest request)
    {
        HttpSession session = request.getSession();
        Integer count = (Integer) session.getAttribute(CAS_FILTER_TICKET_RE_REQUEST_COUNT);
        if (count == null)
            return 0;
        else
            return count;
    }

    private void storeIncrementedTicketReRequestCount(HttpServletRequest request, int count)
    {
        HttpSession session = request.getSession();
        session.setAttribute(CAS_FILTER_TICKET_RE_REQUEST_COUNT, count);
    }

    private boolean requestIsPost(ServletRequest request)
    {
        return ((HttpServletRequest) request).getMethod().equalsIgnoreCase("POST");
    }

    private ServletRequest wrapIfNecessary(ServletRequest request)
    {
        // Wrap the request if desired
        if (wrapRequest && !(request instanceof CASFilterRequestWrapper))
        {
            log.trace("Wrapping request with CASFilterRequestWrapper.");
            return new CASFilterRequestWrapper((HttpServletRequest) request, remoteUserAttrib);
        }
        else
        {
            return request;
        }
    }

    /**
     * CCCI
     *
     * @param request
     * @param response
     */
    private void handleActualLogout(ServletRequest request, ServletResponse response)
    {
        HttpServletRequest req = (HttpServletRequest) request;

        // clear the session
        String[] vals = req.getSession().getValueNames();
        for (int i = 0; i < vals.length; i++)
        {
            req.getSession().removeValue(vals[i]);
        }
        // req.getSession().setAttribute("casLogoutInitiated",Boolean.TRUE);
    }

    /**
     * CCCI
     *
     * @param receipt
     * @return
     */
    private boolean isReceiptQueuedForLogout(CASReceipt receipt)
    {
        String ticket = receipt.getServiceTicket();
        return logoutList.contains(ticket);
    }

    /**
     * CCCI
     *
     * @param request
     * @param response
     */
    private void queueForLogout(ServletRequest request, ServletResponse response)
    {
        if(requestIsPost(request)) return;
        String ticket = request.getParameter("ticket");
        ticket = ticket.substring(1); // remove the leading "-"
        logoutList.add(ticket);
    }

    /**
     * CCCI Is this receipt acceptable as evidence of authentication by
     * credentials that would have been acceptable to this path? Current
     * implementation checks whether from renew and whether proxy was
     * authorized.
     *
     * @param receipt
     * @return true if acceptable, false otherwise
     */
    private boolean isReceiptAcceptable(CASReceipt receipt)
    {
        if (receipt == null) throw new IllegalArgumentException("Cannot evaluate a null receipt.");
        if (this.casRenew && !receipt.isPrimaryAuthentication()) { return false; }
        if (receipt.isProxied())
        {
            if (!this.authorizedProxies.contains(receipt.getProxyingService())) { return false; }
        }
        return true;
    }

    // *********************************************************************
    // Utility methods

    /**
     * Converts a ticket parameter to a CASReceipt, taking into account an
     * optionally configured trusted proxy in the tier immediately in front of
     * us.
     *
     * @throws ServletException
     *             - when unable to get service for request
     * @throws CASAuthenticationException
     *             - on authentication failure
     */
    private CASReceipt getAuthenticatedUser(HttpServletRequest request) throws ServletException,
            CASAuthenticationException
    {
        log.trace("entering getAuthenticatedUser()");
        ProxyTicketValidator pv = null;

        pv = new ProxyTicketValidator();
        pv.setCasValidateUrl(casValidate);
        pv.setServiceTicket(request.getParameter("ticket"));
        pv.setService(getService(request));
        pv.setRenew(casRenew);
        if (casProxyCallbackUrl != null)
        {
            pv.setProxyCallbackUrl(casProxyCallbackUrl);
        }
        if (log.isDebugEnabled())
        {
            log.debug("about to validate ProxyTicketValidator: [" + pv + "]");
        }

        return CASReceipt.getReceipt(pv);

    }

    /**
     * Returns either the configured service or figures it out for the current
     * request. The returned service is URL-encoded.
     */
    private String getService(HttpServletRequest request)
    {

        log.trace("entering getService()");
        String serviceString;

        // ensure we have a server name or service name
        // CCCI commented this out
        // if (casServerName == null && casServiceUrl == null)
        // throw new ServletException(
        // "need one of the following configuration "
        // + "parameters: edu.yale.its.tp.cas.client.filter.serviceUrl or "
        // + "edu.yale.its.tp.cas.client.filter.serverName");

        // use the given string if it's provided
        if (casServiceUrl != null)
            serviceString = URLEncoder.encode(casServiceUrl);
        else
        {
            // otherwise, return our best guess at the service
            serviceString = Util.getService(request, casServerName);
        }

        if (log.isTraceEnabled())
        {
            log.trace("returning from getService() with service [" + serviceString + "]");
        }
        return serviceString;
    }

    /**
     * Redirects the user to CAS, determining the service from the request.
     */
    private void redirectToCAS(HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        if (log.isTraceEnabled())
        {
            log.trace("entering redirectToCAS()");
        }

        String casLoginString = casLogin + "?service=" + getService((HttpServletRequest) request)
                + ((casRenew) ? "&renew=true" : "") + (casGateway ? "&gateway=true" : "");

        if (casLogoutCallbackUrl != null) casLoginString += "&logoutCallback=" + casLogoutCallbackUrl;

        if (log.isDebugEnabled())
        {
            log.debug("Redirecting browser to [" + casLoginString + ")");
        }
        ((HttpServletResponse) response).sendRedirect(casLoginString);

        if (log.isTraceEnabled())
        {
            log.trace("returning from redirectToCAS()");
        }
    }

    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append("[CASFilter:");
        sb.append(" casGateway=");
        sb.append(this.casGateway);
        sb.append(" wrapRequest=");
        sb.append(this.wrapRequest);

        sb.append(" casAuthorizedProxies=[");
        sb.append(this.authorizedProxies);
        sb.append("]");

        if (this.casLogin != null)
        {
            sb.append(" casLogin=[");
            sb.append(this.casLogin);
            sb.append("]");
        }
        else
        {
            sb.append(" casLogin=NULL!!!!!");
        }

        if (this.casProxyCallbackUrl != null)
        {
            sb.append(" casProxyCallbackUrl=[");
            sb.append(casProxyCallbackUrl);
            sb.append("]");
        }

        if (this.casRenew)
        {
            sb.append(" casRenew=true");
        }

        if (this.casServerName != null)
        {
            sb.append(" casServerName=[");
            sb.append(casServerName);
            sb.append("]");
        }

        if (this.casServiceUrl != null)
        {
            sb.append(" casServiceUrl=[");
            sb.append(casServiceUrl);
            sb.append("]");
        }

        if (this.casValidate != null)
        {
            sb.append(" casValidate=[");
            sb.append(casValidate);
            sb.append("]");
        }
        else
        {
            sb.append(" casValidate=NULL!!!");
        }

        return sb.toString();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.servlet.Filter#destroy()
     */
    public void destroy()
    {
        // TODO Auto-generated method stub

    }
}

/*
 * Copyright (c) 2000-2004 Yale University. All rights reserved.
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS," AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE, ARE EXPRESSLY DISCLAIMED. IN NO EVENT SHALL
 * YALE UNIVERSITY OR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED, THE COSTS OF PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED IN ADVANCE OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * Redistribution and use of this software in source or binary forms, with or
 * without modification, are permitted, provided that the following conditions
 * are met:
 * 
 * 1. Any redistribution must include the above copyright notice and disclaimer
 * and this list of conditions in any related documentation and, if feasible, in
 * the redistributed software.
 * 
 * 2. Any redistribution must include the acknowledgment, "This product includes
 * software developed by Yale University," in any related documentation and, if
 * feasible, in the redistributed software.
 * 
 * 3. The names "Yale" and "Yale University" must not be used to endorse or
 * promote products derived from this software.
 */
