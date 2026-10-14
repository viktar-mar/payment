import type { Metadata } from "next";
import { AppShell } from "../components/AppShell";
import "./globals.css";
export const metadata: Metadata = {
  title: "Payment admin",
  description: "Manage sites and payment catalogs",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
