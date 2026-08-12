import { create } from "zustand";

interface UiState {
  navigationCollapsed: boolean;
  setNavigationCollapsed: (collapsed: boolean) => void;
}

export const useUiStore = create<UiState>((set) => ({
  navigationCollapsed: false,
  setNavigationCollapsed: (navigationCollapsed) => set({ navigationCollapsed }),
}));
