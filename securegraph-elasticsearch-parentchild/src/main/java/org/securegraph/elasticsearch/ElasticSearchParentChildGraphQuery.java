package org.securegraph.elasticsearch;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.index.query.*;
import org.securegraph.Authorizations;
import org.securegraph.Graph;
import org.securegraph.PropertyDefinition;
import org.securegraph.elasticsearch.score.ScoringStrategy;

import java.util.List;
import java.util.Map;

public class ElasticSearchParentChildGraphQuery extends ElasticSearchGraphQueryBase {
    protected ElasticSearchParentChildGraphQuery(TransportClient client, String[] indicesToQuery, Graph graph, String queryString, Map<String, PropertyDefinition> propertyDefinitions, ScoringStrategy scoringStrategy, Authorizations authorizations) {
        super(client, indicesToQuery, graph, queryString, propertyDefinitions, scoringStrategy, false, authorizations);
    }

    @Override
    protected QueryBuilder createQuery(String queryString, String elementType, List<FilterBuilder> filters) {
        FilterBuilder elementTypeFilter = createElementTypeFilter(elementType);
        AndFilterBuilder andFilterBuilder = FilterBuilders.andFilter(
                elementTypeFilter,
                new AuthorizationFilterBuilder(getParameters().getAuthorizations().getAuthorizations())
        );

        AuthorizationFilterBuilder authorizationFilterBuilder = new AuthorizationFilterBuilder(getParameters().getAuthorizations().getAuthorizations());

        QueryBuilder childQuery;
        if ((queryString != null && queryString.length() > 0) || (filters.size() > 0)) {
            BoolQueryBuilder boolChildQuery;
            childQuery = boolChildQuery = QueryBuilders.boolQuery();

            if (queryString != null && queryString.length() > 0) {
                boolChildQuery.must(
                        new HasChildQueryBuilder(ElasticSearchParentChildSearchIndex.PROPERTY_TYPE,
                                QueryBuilders.filteredQuery(
                                        super.createQuery(queryString, elementType, filters),
                                        authorizationFilterBuilder
                                )
                        ).scoreType("avg")
                );
            }

            for (FilterBuilder filterBuilder : filters) {
                boolChildQuery.must(
                        new HasChildQueryBuilder(ElasticSearchParentChildSearchIndex.PROPERTY_TYPE,
                                QueryBuilders.filteredQuery(
                                        QueryBuilders.matchAllQuery(),
                                        FilterBuilders.andFilter(authorizationFilterBuilder, filterBuilder)
                                )
                        ).scoreType("avg")
                );
            }
        } else {
            childQuery = QueryBuilders.matchAllQuery();
        }

        return QueryBuilders.filteredQuery(
                childQuery,
                andFilterBuilder
        );
    }

    @Override
    protected void addElementTypeFilter(List<FilterBuilder> filters, String elementType) {
        // don't add the element type filter here because the child docs don't have element type only the parent type does
    }

    @Override
    protected SearchRequestBuilder getSearchRequestBuilder(List<FilterBuilder> filters, QueryBuilder queryBuilder) {
        return getClient()
                .prepareSearch(getIndicesToQuery())
                .setTypes(ElasticSearchSearchIndexBase.ELEMENT_TYPE)
                .setQuery(queryBuilder)
                .setFrom((int) getParameters().getSkip())
                .setSize((int) getParameters().getLimit());
    }

    @Override
    protected void addNotFilter(List<FilterBuilder> filters, String key, Object value) {
        filters.add(
                FilterBuilders.andFilter(
                        FilterBuilders.existsFilter(key),
                        FilterBuilders.notFilter(FilterBuilders.inFilter(key, value))
                )
        );
    }
}
