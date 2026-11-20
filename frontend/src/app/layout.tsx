import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "CODETRACE",
  description: "Evidence-driven codebase change-impact intelligence.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
