// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/vespalib/util/stringfmt.h>
#include <vespa/searchlib/queryeval/leaf_blueprints.h>
#include <vespa/searchlib/queryeval/simpleresult.h>
#include <vespa/searchlib/queryeval/same_element_blueprint.h>
#include <vespa/searchlib/queryeval/same_element_search.h>
#include <vespa/searchlib/queryeval/emptysearch.h>
#include <vespa/searchcommon/attribute/i_search_context.h>
#include <vespa/searchlib/attribute/elementiterator.h>

using namespace search::fef;
using namespace search::queryeval;
using search::attribute::ElementIterator;

std::unique_ptr<SameElementBlueprint> make_blueprint(const std::vector<FakeResult> &children, bool fake_attr = false) {
    auto result = std::make_unique<SameElementBlueprint>(false);
    for (size_t i = 0; i < children.size(); ++i) {
        uint32_t field_id = i;
        vespalib::string field_name = vespalib::make_string("f%u", field_id);
        FieldSpec field = result->getNextChildField(field_name, field_id);
        auto fake = std::make_unique<FakeBlueprint>(field, children[i]);
        fake->is_attr(fake_attr);
        result->addTerm(std::move(fake));
    }
    return result;
}

Blueprint::UP finalize(Blueprint::UP bp, bool strict) {
    Blueprint::UP result = Blueprint::optimize(std::move(bp));
    result->fetchPostings(strict);
    result->freeze();
    return result;
}

SimpleResult find_matches(const std::vector<FakeResult> &children) {
    auto md = MatchData::makeTestInstance(0, 0);
    auto bp = finalize(make_blueprint(children), false);
    auto search = bp->createSearch(*md, false);
    return SimpleResult().search(*search, 1000);
}

FakeResult make_result(const std::vector<std::pair<uint32_t,std::vector<uint32_t> > > &match_data) {
    FakeResult result;
    uint32_t pos_should_be_ignored = 0;
    for (const auto &doc: match_data) {
        result.doc(doc.first);
        for (const auto &elem: doc.second) {
            result.elem(elem).pos(++pos_should_be_ignored);
        }
    }
    return result;
}

TEST("require that simple match can be found") {
    auto a = make_result({{5, {1,3,7}}});
    auto b = make_result({{5, {3,5,10}}});
    SimpleResult result = find_matches({a, b});
    SimpleResult expect({5});
    EXPECT_EQUAL(result, expect);
}

TEST("require that children must match within same element") {
    auto a = make_result({{5, {1,3,7}}});
    auto b = make_result({{5, {2,5,10}}});
    SimpleResult result = find_matches({a, b});
    SimpleResult expect;
    EXPECT_EQUAL(result, expect);
}

TEST("require that strict iterator seeks to next hit") {
    auto md = MatchData::makeTestInstance(0, 0);
    auto a = make_result({{5, {1,2}}, {7, {1,2}}, {8, {1,2}}, {9, {1,2}}});
    auto b = make_result({{5, {3}}, {6, {1,2}}, {7, {2,4}}, {9, {1}}});
    auto bp = finalize(make_blueprint({a,b}), true);
    auto search = bp->createSearch(*md, true);
    search->initRange(1, 1000);
    EXPECT_LESS(search->getDocId(), 1u);
    EXPECT_TRUE(!search->seek(1));
    EXPECT_EQUAL(search->getDocId(), 7u);
    EXPECT_TRUE(search->seek(9));
    EXPECT_EQUAL(search->getDocId(), 9u);
    EXPECT_TRUE(!search->seek(10));
    EXPECT_TRUE(search->isAtEnd());
}

TEST("require that results are estimated appropriately") {
    auto a = make_result({{5, {0}}, {5, {0}}, {5, {0}}});
    auto b = make_result({{5, {0}}, {5, {0}}});
    auto c = make_result({{5, {0}}, {5, {0}}, {5, {0}}, {5, {0}}});
    auto bp = finalize(make_blueprint({a,b,c}), true);
    EXPECT_EQUAL(bp->getState().estimate().estHits, 2u);
}

TEST("require that children are sorted") {
    auto a = make_result({{5, {0}}, {5, {0}}, {5, {0}}});
    auto b = make_result({{5, {0}}, {5, {0}}});
    auto c = make_result({{5, {0}}, {5, {0}}, {5, {0}}, {5, {0}}});
    auto bp = finalize(make_blueprint({a,b,c}), true);
    EXPECT_EQUAL(dynamic_cast<SameElementBlueprint&>(*bp).terms()[0]->getState().estimate().estHits, 2u);
    EXPECT_EQUAL(dynamic_cast<SameElementBlueprint&>(*bp).terms()[1]->getState().estimate().estHits, 3u);
    EXPECT_EQUAL(dynamic_cast<SameElementBlueprint&>(*bp).terms()[2]->getState().estimate().estHits, 4u);
}

TEST("require that attribute iterators are wrapped for element unpacking") {
    auto a = make_result({{5, {1,3,7}}});
    auto b = make_result({{5, {3,5,10}}});
    auto bp = finalize(make_blueprint({a,b}, true), true);
    auto md = MatchData::makeTestInstance(0, 0);
    auto search = bp->createSearch(*md, false);
    SameElementSearch *se = dynamic_cast<SameElementSearch*>(search.get());
    ASSERT_TRUE(se != nullptr);
    ASSERT_EQUAL(se->children().size(), 2u);
    EXPECT_TRUE(dynamic_cast<ElementIterator*>(se->children()[0].get()) != nullptr);
    EXPECT_TRUE(dynamic_cast<ElementIterator*>(se->children()[1].get()) != nullptr);   
}

TEST_MAIN() { TEST_RUN_ALL(); }
