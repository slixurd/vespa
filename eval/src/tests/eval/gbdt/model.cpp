// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <random>
#include <vespa/eval/eval/function.h>

using vespalib::make_string;
using vespalib::eval::Function;

//-----------------------------------------------------------------------------

class Model
{
private:
    std::mt19937 _gen;
    size_t _less_percent;
    size_t _invert_percent;

    size_t get_int(size_t min, size_t max) {
        std::uniform_int_distribution<size_t> dist(min, max);
        return dist(_gen);
    }

    double get_real(double min, double max) {
        std::uniform_real_distribution<double> dist(min, max);
        return dist(_gen);
    }

    std::string make_feature_name() {
        size_t max_feature = 2;
        while ((max_feature < 1024) && (get_int(0, 99) < 55)) {
            max_feature *= 2;
        }
        return make_string("feature_%zu", get_int(1, max_feature));
    }

    std::string make_cond() {
        if (get_int(1,100) > _less_percent) {
            return make_string("(%s in [%g,%g,%g])",
                               make_feature_name().c_str(),
                               get_int(0, 4) / 4.0,
                               get_int(0, 4) / 4.0,
                               get_int(0, 4) / 4.0);
        } else {
            if (get_int(1,100) > _invert_percent) {
                return make_string("(%s<%g)",
                                   make_feature_name().c_str(),
                                   get_real(0.0, 1.0));
            } else {
                return make_string("(!(%s>=%g))",
                                   make_feature_name().c_str(),
                                   get_real(0.0, 1.0));
            }
        }
    }

public:
    explicit Model(size_t seed = 5489u) : _gen(seed), _less_percent(80), _invert_percent(0) {}

    Model &less_percent(size_t value) {
        _less_percent = value;
        return *this;
    }

    Model &invert_percent(size_t value) {
        _invert_percent = value;
        return *this;
    }

    std::string make_tree(size_t size) {
        assert(size > 0);
        if (size == 1) {
            return make_string("%g", get_real(0.0, 1.0));
        }
        size_t pivot = get_int(1, size - 1);
        return make_string("if(%s,%s,%s)",
                           make_cond().c_str(),
                           make_tree(pivot).c_str(),
                           make_tree(size - pivot).c_str());
    }

    std::string make_forest(size_t num_trees, size_t tree_sizes) {
        assert(num_trees > 0);
        vespalib::string forest = make_tree(tree_sizes);
        for (size_t i = 1; i < num_trees; ++i) {
            forest.append("+");
            forest.append(make_tree(tree_sizes));
        }
        return forest;
    }
};

//-----------------------------------------------------------------------------

struct ForestParams {
    size_t model_seed;
    size_t less_percent;
    size_t tree_size;
    ForestParams(size_t model_seed_in, size_t less_percent_in, size_t tree_size_in)
        : model_seed(model_seed_in), less_percent(less_percent_in), tree_size(tree_size_in) {}
};

//-----------------------------------------------------------------------------

Function make_forest(const ForestParams &params, size_t num_trees) {
    return Function::parse(Model(params.model_seed)
                           .less_percent(params.less_percent)
                           .make_forest(num_trees, params.tree_size));
}

//-----------------------------------------------------------------------------
