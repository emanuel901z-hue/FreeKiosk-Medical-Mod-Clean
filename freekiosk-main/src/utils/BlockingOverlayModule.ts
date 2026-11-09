/**
 * FreeKiosk - Blocking Overlay Module
 * 
 * React Native bridge for native blocking overlay functionality
 */

import { NativeModules } from 'react-native';
import { BlockingRegion } from '../types/blockingOverlay';

interface BlockingOverlayModuleType {
  setEnabled(enabled: boolean): Promise<boolean>;
  isEnabled(): Promise<boolean>;
  setRegions(regionsJson: string): Promise<boolean>;
  updateOverlays(): Promise<boolean>;
  removeAllOverlays(): Promise<boolean>;
  showTouchLogger(durationSeconds: number): Promise<boolean>;
  hideTouchLogger(): Promise<boolean>;
  showGridHelper(durationSeconds: number): Promise<boolean>;
  hideGridHelper(): Promise<boolean>;
  showHomeButton(xPercent: number, yPercent: number): Promise<boolean>;
  hideHomeButton(): Promise<boolean>;
  showBanner(text: string, fontSize: number, showIcon: boolean): Promise<boolean>;
  hideBanner(): Promise<boolean>;
}

const BlockingOverlayNative: BlockingOverlayModuleType = NativeModules.BlockingOverlayModule;

/**
 * Module for managing blocking overlays from React Native
 */
export const BlockingOverlayModule = {
  /**
   * Enable or disable blocking overlays globally
   */
  setEnabled: async (enabled: boolean): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.setEnabled(enabled);
    } catch (error) {
      console.error('BlockingOverlayModule.setEnabled error:', error);
      return false;
    }
  },

  /**
   * Check if blocking overlays are enabled
   */
  isEnabled: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.isEnabled();
    } catch (error) {
      console.error('BlockingOverlayModule.isEnabled error:', error);
      return false;
    }
  },

  /**
   * Set the list of blocking regions
   */
  setRegions: async (regions: BlockingRegion[]): Promise<boolean> => {
    try {
      const json = JSON.stringify(regions);
      return await BlockingOverlayNative.setRegions(json);
    } catch (error) {
      console.error('BlockingOverlayModule.setRegions error:', error);
      return false;
    }
  },

  /**
   * Force update all overlays
   */
  updateOverlays: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.updateOverlays();
    } catch (error) {
      console.error('BlockingOverlayModule.updateOverlays error:', error);
      return false;
    }
  },

  /**
   * Remove all blocking overlays
   */
  removeAllOverlays: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.removeAllOverlays();
    } catch (error) {
      console.error('BlockingOverlayModule.removeAllOverlays error:', error);
      return false;
    }
  },

  /**
   * Show touch logger overlay that displays touch coordinates
   * @param seconds Duration to show (default 30)
   */
  showTouchLogger: async (seconds: number = 30): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.showTouchLogger(seconds);
    } catch (error) {
      console.error('BlockingOverlayModule.showTouchLogger error:', error);
      return false;
    }
  },

  /**
   * Hide touch logger overlay
   */
  hideTouchLogger: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.hideTouchLogger();
    } catch (error) {
      console.error('BlockingOverlayModule.hideTouchLogger error:', error);
      return false;
    }
  },

  /**
   * Show grid helper overlay with percentage markers
   * @param seconds Duration to show (default 30)
   */
  showGridHelper: async (seconds: number = 30): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.showGridHelper(seconds);
    } catch (error) {
      console.error('BlockingOverlayModule.showGridHelper error:', error);
      return false;
    }
  },

  /**
   * Hide grid helper overlay
   */
  hideGridHelper: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.hideGridHelper();
    } catch (error) {
      console.error('BlockingOverlayModule.hideGridHelper error:', error);
      return false;
    }
  },

  /**
   * Show a native home button overlay (floats above the WebView, receives touch)
   * @param xPercent  Horizontal position as percentage (0-100)
   * @param yPercent  Vertical position as percentage (0-100)
   */
  showHomeButton: async (xPercent: number, yPercent: number): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.showHomeButton(xPercent, yPercent);
    } catch (error) {
      console.error('BlockingOverlayModule.showHomeButton error:', error);
      return false;
    }
  },

  /**
   * Hide the native home button overlay
   */
  hideHomeButton: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.hideHomeButton();
    } catch (error) {
      console.error('BlockingOverlayModule.hideHomeButton error:', error);
      return false;
    }
  },

  /**
   * Show a native anti-theft banner overlay at the top of the screen
   */
  showBanner: async (text: string, fontSize: number = 14, showIcon: boolean = true): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.showBanner(text, fontSize, showIcon);
    } catch (error) {
      console.error('BlockingOverlayModule.showBanner error:', error);
      return false;
    }
  },

  /**
   * Hide the native banner overlay
   */
  hideBanner: async (): Promise<boolean> => {
    try {
      return await BlockingOverlayNative.hideBanner();
    } catch (error) {
      console.error('BlockingOverlayModule.hideBanner error:', error);
      return false;
    }
  },

  /**
   * Apply configuration and sync with native side
   */
  applyConfiguration: async (enabled: boolean, regions: BlockingRegion[]): Promise<boolean> => {
    try {
      await BlockingOverlayModule.setRegions(regions);
      await BlockingOverlayModule.setEnabled(enabled);
      return true;
    } catch (error) {
      console.error('BlockingOverlayModule.applyConfiguration error:', error);
      return false;
    }
  },
};

export default BlockingOverlayModule;
