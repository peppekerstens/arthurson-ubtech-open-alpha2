package com.open.alpha2;

/**
 * 2026-09: moved in from sdk-module/ubtechalpha2robot (part of the split
 * from Alpha2OpenSdk).
 * Result codes for robot-hardware calls. Meaning unchanged:
 *
 * <ul>
 *   <li>{@code API_ERROR_SUCCEED} - the call was accepted / sent to the hardware.</li>
 *   <li>{@code API_ERROR_NOT_INIT} - alpha2services is gone from the robot, so
 *       every old binder path returns this (see {@link RobotStub}).</li>
 *   <li>{@code API_ERROR_APPID_NOT_ACTIVE} / {@code API_ERROR_AUTHORIZE_ERROR} -
 *       legacy store-authorisation failures, never returned in normal operation.</li>
 *   <li>{@code API_ERROR_FAILED} - direct serial send failed (port unavailable /
 *       write error); see MainActivity.directCode().</li>
 * </ul>
 */
public final class UbxErrorCode {
   private UbxErrorCode() {
   }

   public enum API_ERROR_CODE {
      API_ERROR_NOT_INIT,
      API_ERROR_SUCCEED,
      API_ERROR_APPID_NOT_ACTIVE,
      API_ERROR_AUTHORIZE_ERROR,
      API_ERROR_FAILED
   }
}
