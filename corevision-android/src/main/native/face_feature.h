#ifndef COREVISION_FACE_FEATURE_H
#define COREVISION_FACE_FEATURE_H

#include "wire.h"

#include <array>
#include <string>

using FaceFeatures = std::array<float, CV_FACE_FEATURES>;

bool extractFaceFeatures(const CvRequest &request, const uint8_t *rgba, const CvFace &face,
                         const std::string &modelDir, FaceFeatures &features);

#endif
