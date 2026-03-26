package com.auraboot.framework.base.feature;

import lombok.Data;

import java.util.List;
@Data
public class DefaultFeatureVector implements FeatureVector {

    private List<Feature> featureList;

}
