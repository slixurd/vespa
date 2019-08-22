// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/attribute/numericbase.h>
#include <vespa/searchlib/attribute/stringbase.h>
#include <vespa/searchlib/attribute/enumattribute.h>
#include <vespa/searchlib/queryeval/searchiterator.h>
#include <vespa/vespalib/datastore/entryref.h>
#include <vespa/vespalib/btree/btreestore.h>
#include "dociditerator.h"
#include "postinglistsearchcontext.h"
#include "postingchange.h"
#include "ipostinglistattributebase.h"

namespace search {

class EnumPostingPair
{
private:
    EnumStoreBase::Index _idx;
    const EnumStoreComparator *_cmp;
public:
    EnumPostingPair(EnumStoreBase::Index idx, const EnumStoreComparator *cmp)
        : _idx(idx),
          _cmp(cmp)
    { }

    bool operator<(const EnumPostingPair &rhs) const { return (*_cmp)(_idx, rhs._idx); }
    EnumStoreBase::Index getEnumIdx() const { return _idx; }
};


template <typename P>
class PostingListAttributeBase : public attribute::IPostingListAttributeBase {
protected:
    using Posting = P;
    using DataType = typename Posting::DataType;

    using AggregationTraits = attribute::PostingListTraits<DataType>;
    using DocId = AttributeVector::DocId;
    using EntryRef = datastore::EntryRef;
    using EnumIndex = EnumStoreBase::Index;
    using LoadedEnumAttributeVector = attribute::LoadedEnumAttributeVector;
    using PostingList = typename AggregationTraits::PostingList;
    using PostingMap = std::map<EnumPostingPair, PostingChange<P> >;

    PostingList _postingList;
    AttributeVector &_attr;
    EnumPostingTree &_dict;
    EnumStoreBase   &_esb;

    PostingListAttributeBase(AttributeVector &attr, EnumStoreBase &enumStore);
    virtual ~PostingListAttributeBase();

    virtual void updatePostings(PostingMap & changePost) = 0;

    void updatePostings(PostingMap &changePost, EnumStoreComparator &cmp);
    void clearAllPostings();
    void disableFreeLists() { _postingList.disableFreeLists(); }
    void disableElemHoldList() { _postingList.disableElemHoldList(); }
    void fillPostingsFixupEnumBase(const LoadedEnumAttributeVector &loaded);
    bool forwardedOnAddDoc(DocId doc, size_t wantSize, size_t wantCapacity);

    void clearPostings(attribute::IAttributeVector::EnumHandle eidx, uint32_t fromLid,
                       uint32_t toLid, EnumStoreComparator &cmp);

    void forwardedShrinkLidSpace(uint32_t newSize) override;
    virtual vespalib::MemoryUsage getMemoryUsage() const override;

public:
    const PostingList & getPostingList() const { return _postingList; }
    PostingList & getPostingList()             { return _postingList; }
};

template <typename P, typename LoadedVector, typename LoadedValueType,
          typename EnumStoreType>
class PostingListAttributeSubBase : public PostingListAttributeBase<P> {
public:
    using Parent = PostingListAttributeBase<P>;

    using Dictionary = EnumPostingTree;
    using EntryRef = datastore::EntryRef;
    using EnumIndex = EnumStoreBase::Index;
    using EnumStore = EnumStoreType;
    using FoldedComparatorType = typename EnumStore::FoldedComparatorType;
    using LoadedEnumAttributeVector = attribute::LoadedEnumAttributeVector;
    using PostingList = typename Parent::PostingList;
    using PostingMap = typename Parent::PostingMap;

    using Parent::clearAllPostings;
    using Parent::updatePostings;
    using Parent::fillPostingsFixupEnumBase;
    using Parent::clearPostings;
    using Parent::_postingList;
    using Parent::_attr;
    using Parent::_dict;

private:
    EnumStore &_es;

public:
    PostingListAttributeSubBase(AttributeVector &attr, EnumStore &enumStore);
    virtual ~PostingListAttributeSubBase();

    void handleFillPostings(LoadedVector &loaded);
    void updatePostings(PostingMap &changePost) override;
    void clearPostings(attribute::IAttributeVector::EnumHandle eidx, uint32_t fromLid, uint32_t toLid) override;
};

extern template class PostingListAttributeBase<AttributePosting>;
extern template class PostingListAttributeBase<AttributeWeightPosting>;

} // namespace search
