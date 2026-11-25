#ifndef COREVISION_TEXT_FEATURE_H
#define COREVISION_TEXT_FEATURE_H

#include "wire.h"

#include <array>
#include <string>

using SearchFeatures = std::array<float, CV_SEARCH_FEATURES>;

bool extractTextFeatures(const std::string &nativeDir, const std::string &models,
                         const std::string &query, SearchFeatures &features);

#endif
