import type { Metadata } from "next";
import AppHeader from "@/components/AppHeader";
import "./globals.css";

export const metadata: Metadata = {
  title: "CODETRACE",
  description: "See what a code change could break, with evidence for every claim.",
};

// Applies a saved light/dark choice before the first paint, so the page never flashes the wrong theme.
const THEME_SCRIPT = `try{var t=localStorage.getItem("codetrace:theme");if(t==="light"||t==="dark")document.documentElement.setAttribute("data-theme",t)}catch(e){}`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body>
        <AppHeader />
        {children}
      </body>
    </html>
  );
}
