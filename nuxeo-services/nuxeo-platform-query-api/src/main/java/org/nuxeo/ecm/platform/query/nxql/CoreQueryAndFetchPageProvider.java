/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Anahide Tchertchian
 */
package org.nuxeo.ecm.platform.query.nxql;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.platform.query.api.AbstractPageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.query.api.PageSelections;

/**
 * Page provider performing a queryAndFetch on a core session.
 * <p>
 * It builds the query at each call so that it can refresh itself when the query changes.
 * <p>
 * <p>
 * The page provider property named {@link #CORE_SESSION_PROPERTY} is used to pass the {@link CoreSession} instance that
 * will perform the query. The optional property {@link #CHECK_QUERY_CACHE_PROPERTY} can be set to "true" to avoid
 * performing the query again if it did not change.
 * <p>
 * Since 6.0, the page provider property named {@link #LANGUAGE_PROPERTY} allows specifying the query language (NXQL,
 * NXTAG,...).
 * <p>
 * Also since 6.0, the page provider property named {@link #USE_UNRESTRICTED_SESSION_PROPERTY} allows specifying whether
 * the query should be run as unrestricted.
 *
 * @author Anahide Tchertchian
 * @since 5.4
 */
public class CoreQueryAndFetchPageProvider extends AbstractPageProvider<Map<String, Serializable>> {

    public static final String CORE_SESSION_PROPERTY = "coreSession";

    public static final String CHECK_QUERY_CACHE_PROPERTY = "checkQueryCache";

    /**
     * Boolean property stating that query should be unrestricted.
     *
     * @since 6.0
     */
    public static final String USE_UNRESTRICTED_SESSION_PROPERTY = "useUnrestrictedSession";

    /**
     * @since 6.0: alow specifying the query language (NXQL, NXTAG,...)
     */
    public static final String LANGUAGE_PROPERTY = "language";

    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(CoreQueryDocumentPageProvider.class);

    protected String query;

    protected List<Map<String, Serializable>> currentItems;

    protected CoreSession getCoreSession() {
        CoreSession coreSession = null;
        Map<String, Serializable> props = getProperties();
        coreSession = (CoreSession) props.get(CORE_SESSION_PROPERTY);
        return coreSession;
    }

    @Override
    public List<Map<String, Serializable>> getCurrentPage() {
        checkQueryCache();
        CoreSession coreSession = null;
        long t0 = System.currentTimeMillis();
        if (currentItems == null) {
            errorMessage = null;
            error = null;

            if (query == null) {
                buildQuery();
            }
            if (query == null) {
                throw new NuxeoException(String.format("Cannot perform null query: check provider '%s'", getName()));
            }

            currentItems = new ArrayList<Map<String, Serializable>>();

            Map<String, Serializable> props = getProperties();
            coreSession = getCoreSession();
            if (coreSession == null) {
                throw new NuxeoException("cannot find core session");
            }

            IterableQueryResult result = null;
            try {

                long minMaxPageSize = getMinMaxPageSize();

                long offset = getCurrentPageOffset();
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Perform query for provider '%s': '%s' with pageSize=%s, offset=%s",
                            getName(), query, Long.valueOf(minMaxPageSize), Long.valueOf(offset)));
                }

                final String language = getQueryLanguage();
                final boolean useUnrestricted = useUnrestrictedSession();
                if (useUnrestricted) {
                    CoreQueryAndFetchUnrestrictedSessionRunner r = new CoreQueryAndFetchUnrestrictedSessionRunner(
                            coreSession, query, language);
                    r.runUnrestricted();
                    result = r.getResult();
                } else {
                    result = coreSession.queryAndFetch(query, language);
                }
                long resultsCount = result.size();
                setResultsCount(resultsCount);
                if (offset < resultsCount) {
                    result.skipTo(offset);
                }

                Iterator<Map<String, Serializable>> it = result.iterator();
                int pos = 0;
                while (it.hasNext() && (maxPageSize == 0 || pos < minMaxPageSize)) {
                    pos += 1;
                    Map<String, Serializable> item = it.next();
                    currentItems.add(item);
                }

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Performed query for provider '%s': got %s hits", getName(),
                            Long.valueOf(resultsCount)));
                }

            } catch (NuxeoException e) {
                errorMessage = e.getMessage();
                error = e;
                log.warn(e.getMessage(), e);
            } finally {
                if (result != null) {
                    result.close();
                }
            }
        }

        if (coreSession == null) {
            coreSession = getCoreSession();
        }

        // send event for statistics !
        fireSearchEvent(coreSession.getPrincipal(), query, currentItems, System.currentTimeMillis() - t0);

        return currentItems;
    }

    protected void buildQuery() {
        SortInfo[] sortArray = null;
        if (sortInfos != null) {
            sortArray = sortInfos.toArray(new SortInfo[] {});
        }
        String newQuery;
        PageProviderDefinition def = getDefinition();
        if (def.getWhereClause() == null) {
            newQuery = NXQLQueryBuilder.getQuery(def.getPattern(), getParameters(), def.getQuotePatternParameters(),
                    def.getEscapePatternParameters(), getSearchDocumentModel(), sortArray);
        } else {
            DocumentModel searchDocumentModel = getSearchDocumentModel();
            if (searchDocumentModel == null) {
                throw new NuxeoException(String.format("Cannot build query of provider '%s': "
                        + "no search document model is set", getName()));
            }
            newQuery = NXQLQueryBuilder.getQuery(searchDocumentModel, def.getWhereClause(), getParameters(), sortArray);
        }

        if (query != null && newQuery != null && !newQuery.equals(query)) {
            // query has changed => refresh
            refresh();
        }
        query = newQuery;
    }

    @Override
    public PageSelections<Map<String, Serializable>> getCurrentSelectPage() {
        checkQueryCache();
        // fetch last page if current page index is beyond the last page or if there are no results to display
        rewindSelectablePage();
        return super.getCurrentSelectPage();
    }

    /**
     * Fetch a page that can be selected. It loads the last page if we're targeting a page beyond the last one or
     * the first page if there are no results to show and we're targeting anything other than the first page.
     *
     * Fix for NXP-8564.
     */
    protected void rewindSelectablePage() {
        long pageSize = getPageSize();
        if (pageSize != 0) {
            if (offset != 0 && currentItems != null && currentItems.size() == 0) {
                if (resultsCount == 0) {
                    // fetch first page directly
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Current page %s is not the first one but "
                                + "shows no result and there are " + "no results => rewind to first page",
                            Long.valueOf(getCurrentPageIndex())));
                    }
                    firstPage();
                } else {
                    // fetch last page
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Current page %s is not the first one but "
                                + "shows no result and there are " + "%s results => fetch last page",
                            Long.valueOf(getCurrentPageIndex()), Long.valueOf(resultsCount)));
                    }
                    lastPage();
                }
                // fetch current page again
                getCurrentPage();
            }
        }
    }

    protected void checkQueryCache() {
        // maybe handle refresh of select page according to query
        if (getBooleanProperty(CHECK_QUERY_CACHE_PROPERTY, false)) {
            buildQuery();
        }
    }

    protected boolean useUnrestrictedSession() {
        return getBooleanProperty(USE_UNRESTRICTED_SESSION_PROPERTY, false);
    }

    protected String getQueryLanguage() {
        Map<String, Serializable> props = getProperties();
        if (props.containsKey(LANGUAGE_PROPERTY)) {
            return (String) props.get(LANGUAGE_PROPERTY);
        }
        return NXQL.NXQL;
    }

    public String getCurrentQuery() {
        return query;
    }

    @Override
    protected void pageChanged() {
        currentItems = null;
        super.pageChanged();
    }

    @Override
    public void refresh() {
        query = null;
        currentItems = null;
        super.refresh();
    }

}