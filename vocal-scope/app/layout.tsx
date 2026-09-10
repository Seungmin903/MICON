import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import './globals.css';

const geistSans = Geist({
  variable: '--font-geist-sans',
  subsets: ['latin'],
});

const geistMono = Geist_Mono({
  variable: '--font-geist-mono',
  subsets: ['latin'],
});

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL || 'https://vocal-scope.dawon171100.chatgpt.site'),
  title: 'VocalScope — 보컬 음역 분석 스튜디오',
  description: '로컬 AI 모델로 음원에서 보컬을 분리하고 악보와 음역을 분석하는 음악 도구',
  openGraph: {
    title: 'VocalScope — 보컬 음역 분석 스튜디오',
    description: '음원에서 보컬을 분리하고 악보와 음역을 분석하세요.',
    images: [{ url: '/og.png', width: 1200, height: 630, alt: 'VocalScope 보컬 음역 분석 스튜디오' }],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'VocalScope — 보컬 음역 분석 스튜디오',
    description: '음원에서 보컬을 분리하고 악보와 음역을 분석하세요.',
    images: ['/og.png'],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      </body>
    </html>
  );
}
