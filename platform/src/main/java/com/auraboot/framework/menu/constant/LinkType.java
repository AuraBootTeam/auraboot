package com.auraboot.framework.menu.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;


  @Getter
  public enum LinkType {
  ROUTE("route", "路由"),
  SCHEMA("schema", "页面Schema"),
  TAB("tab", "标签页"),
  EXTERNAL("external", "外部链接");

  @EnumValue
  @JsonValue
  private final String code;
  private final String name;

  LinkType(String code, String name) {
  this.code = code;
  this.name = name;
  }
  }

