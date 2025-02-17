// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "i_constant_value_repo.h"
#include "indexenvironment.h"
#include "matching_stats.h"
#include "search_session.h"
#include "viewresolver.h"
#include <vespa/searchcore/proton/matching/querylimiter.h>
#include <vespa/searchcommon/attribute/i_attribute_functor.h>
#include <vespa/searchlib/common/featureset.h>
#include <vespa/searchlib/common/struct_field_mapper.h>
#include <vespa/searchlib/common/matching_elements.h>
#include <vespa/searchlib/common/resultset.h>
#include <vespa/searchlib/queryeval/blueprint.h>
#include <vespa/searchlib/fef/fef.h>
#include <vespa/searchlib/query/base.h>
#include <vespa/vespalib/util/clock.h>
#include <vespa/vespalib/util/closure.h>
#include <vespa/vespalib/util/thread_bundle.h>
#include <mutex>

namespace search::grouping {
    class GroupingContext;
    class GroupingSession;
}
namespace search::index { class Schema; }
namespace search::attribute { class IAttributeContext; }
namespace search::engine {
    class Request;
    class SearchRequest;
    class DocsumRequest;
    class SearchReply;
}
namespace search { struct IDocumentMetaStore; }

namespace proton::matching {

class ISearchContext;
class SessionManager;
class MatchToolsFactory;

/**
 * The Matcher is responsible for performing searches.
 **/
class Matcher
{
private:
    using IAttributeContext = search::attribute::IAttributeContext;
    using DocsumRequest = search::engine::DocsumRequest;
    using Properties = search::fef::Properties;
    using my_clock = std::chrono::steady_clock;
    using StructFieldMapper = search::StructFieldMapper;
    using MatchingElements = search::MatchingElements;
    IndexEnvironment              _indexEnv;
    search::fef::BlueprintFactory _blueprintFactory;
    search::fef::RankSetup::SP    _rankSetup;
    ViewResolver                  _viewResolver;
    std::mutex                    _statsLock;
    MatchingStats                 _stats;
    my_clock::time_point          _startTime;
    const vespalib::Clock        &_clock;
    QueryLimiter                 &_queryLimiter;
    uint32_t                      _distributionKey;

    search::FeatureSet::SP
    getFeatureSet(const DocsumRequest & req, ISearchContext & searchCtx, IAttributeContext & attrCtx,
                  SessionManager &sessionMgr, bool summaryFeatures);
    std::unique_ptr<search::engine::SearchReply>
    handleGroupingSession(SessionManager &sessionMgr,
                          search::grouping::GroupingContext & groupingContext,
                          std::unique_ptr<search::grouping::GroupingSession> gs);

    size_t computeNumThreadsPerSearch(search::queryeval::Blueprint::HitEstimate hits,
                                      const Properties & rankProperties) const;
public:
    /**
     * Convenience typedefs.
     */
    typedef std::shared_ptr<Matcher> SP;


    Matcher(const Matcher &) = delete;
    Matcher &operator=(const Matcher &) = delete;

    /**
     * Create a new matcher. The schema represents the current index
     * layout.
     *
     * @param schema index schema
     * @param props ranking configuration
     * @param clock used for timeout handling
     **/
    Matcher(const search::index::Schema &schema, const Properties &props,
            const vespalib::Clock &clock, QueryLimiter &queryLimiter,
            const IConstantValueRepo &constantValueRepo, uint32_t distributionKey);

    const search::fef::IIndexEnvironment &get_index_env() const { return _indexEnv; }

    /**
     * Observe and reset stats for this object.
     *
     * @return stats
     **/
    MatchingStats getStats();

    /**
     * Create the low-level tools needed to perform matching. This
     * function is exposed for testing purposes.
     **/
    std::unique_ptr<MatchToolsFactory>
    create_match_tools_factory(const search::engine::Request &request, ISearchContext &searchContext,
                               IAttributeContext &attrContext, const search::IDocumentMetaStore &metaStore,
                               const Properties &feature_overrides) const;

    /**
     * Perform a search against this matcher.
     *
     * @return search reply
     * @param request the search request
     * @param threadBundle bundle of threads to use for multi-threaded execution
     * @param searchContext abstract view of searchable data
     * @param attrContext abstract view of attribute data
     * @param sessionManager multilevel grouping session cache
     * @param metaStore the document meta store used to map from lid to gid
     **/
    std::unique_ptr<search::engine::SearchReply>
    match(const search::engine::SearchRequest &request, vespalib::ThreadBundle &threadBundle,
          ISearchContext &searchContext, IAttributeContext &attrContext,
          SessionManager &sessionManager, const search::IDocumentMetaStore &metaStore,
          SearchSession::OwnershipBundle &&owned_objects);

    /**
     * Perform matching for the documents in the given docsum request
     * to calculate the summary features specified in the rank setup
     * of this matcher.
     *
     * @param req the docsum request
     * @param searchCtx abstract view of searchable data
     * @param attrCtx abstract view of attribute data
     * @return calculated summary features.
     **/
    search::FeatureSet::SP
    getSummaryFeatures(const DocsumRequest & req, ISearchContext & searchCtx,
                       IAttributeContext & attrCtx, SessionManager &sessionManager);

    /**
     * Perform matching for the documents in the given docsum request
     * to calculate the rank features specified in the rank setup of
     * this matcher.
     *
     * @param req the docsum request
     * @param searchCtx abstract view of searchable data
     * @param attrCtx abstract view of attribute data
     * @return calculated rank features.
     **/
    search::FeatureSet::SP
    getRankFeatures(const DocsumRequest & req, ISearchContext & searchCtx,
                    IAttributeContext & attrCtx, SessionManager &sessionManager);

    /**
     * Perform partial matching for the documents in the given docsum request
     * to identify which struct field elements the query matched.
     *
     * @param req the docsum request
     * @param search_ctx abstract view of searchable data
     * @param attr_ctx abstract view of attribute data
     * @param session_manager multilevel grouping session and query cache
     * @param field_mapper knows which fields to collect information
     *                     about and how they relate to each other
     * @return matching elements
     **/
    MatchingElements get_matching_elements(const DocsumRequest &req, ISearchContext &search_ctx,
                                           IAttributeContext &attr_ctx, SessionManager &session_manager,
                                           const StructFieldMapper &field_mapper);

    /**
     * @return true if this rankprofile has summary-features enabled
     **/
    bool canProduceSummaryFeatures() const { return ! _rankSetup->getSummaryFeatures().empty(); }
    double get_termwise_limit() const { return _rankSetup->get_termwise_limit(); }
};

}
