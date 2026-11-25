#ifndef COREVISION_OCR_BRIDGE_H
#define COREVISION_OCR_BRIDGE_H

#include "wire.h"

#include <string>

int recognizeText(const std::string &nativeDir, const std::string &models, const std::string &cachePath,
                  const CvRequest &request, uint8_t *rgba, std::string &result, std::string &error);

#endif
