/*
 * Copyright (C) 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mediatek.powerhalmgr;

public class PowerHalMgrImpl {

  public PowerHalMgrImpl() {
  }

  public int scnReg() {
    return 1;
  }

  public void scnConfig(int handle, int cmd,
      int param1, int param2, int param3) {
  }

  public void scnEnable(int handle) {
  }

  public void scnDisable() {
  }

  public void scnUnreg() {
  }

  public int perfLockAcquire(int handle, int duration, int[] params) {
    return 1;
  }

  public void perfLockRelease() {
  }

  public void perfLockRelease(int handle) {
  }

  public void mtkPowerHint(int hint, int data) {
  }

  public int perfCusLockHint(int hint, int data) {
    return 0;
  }
}
