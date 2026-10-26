package com.chemlab.model;

/** 已保存的标定曲线。x/y 以 JSON 数组文本存储，回归结果一并缓存。 */
public class Calibration {

    public long id;
    public String name = "";
    public String xJson = "[]";
    public String yJson = "[]";
    public double slope;
    public double intercept;
    public double r2;
    public double syx;
    public double lod;
    public double loq;
    public String createdAt = "";

    public Calibration() {
    }

    @Override
    public String toString() {
        return name;
    }
}