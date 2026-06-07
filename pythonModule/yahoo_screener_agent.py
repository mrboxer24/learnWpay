#yahoo_screener_agent.py:

import os

import time

import json

import pandas as pd

import yfinance as yf

from datetime import datetime, time as dtime



# ---------- CONFIG ----------

TICKER_UNIVERSE = ["AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOG", "NFLX", "AMD", "INTC"]

TOP_N = 5

# ----------------------------



def fetch_market_data(tickers):

    """Fetch data for given tickers from Yahoo Finance"""

    data = []

    for t in tickers:

        try:

            ticker = yf.Ticker(t)

            info = ticker.info

            hist = ticker.history(period="6mo")

            close = hist["Close"].iloc[-1]

            rev_growth = info.get("revenueGrowth")

            pe = info.get("trailingPE") or info.get("forwardPE")

            mcap = info.get("marketCap")

            sector = info.get("sector")

            avgvol = info.get("averageVolume10days") or info.get("averageVolume")

            data.append({

                "ticker": t,

                "price": close,

                "pe": pe,

                "market_cap": mcap,

                "revenue_growth": rev_growth,

                "sector": sector,

                "avg_vol": avgvol

            })

        except Exception as e:

            print(f"⚠️ Error fetching {t}: {e}")

    return pd.DataFrame(data)



def filter_tickers(df):

    """Filter: price > $20, PE < 40, rev growth > 0, market cap > 5B"""

    return df[

        (df["price"] > 20) &

        (df["pe"] < 40) &

        (df["revenue_growth"] > 0) &

        (df["market_cap"] > 5e9)

        ]



def rank_and_explain(df):

    """Simple ranking formula"""

    df["score"] = df["revenue_growth"] / (df["pe"] + 1e-6)

    df = df.sort_values("score", ascending=False).head(TOP_N)

    results = []

    for _, r in df.iterrows():

        results.append({

            "ticker": r.ticker,

            "score": round(float(r.score), 6),

            "explanation": f"PE={r.pe}, rev_growth={r.revenue_growth}, mcap={r.market_cap}"

        })

    return results



def run_screener():

    df = fetch_market_data(TICKER_UNIVERSE)

    df = filter_tickers(df.dropna(subset=["price", "pe", "revenue_growth", "market_cap"]))

    ranked = rank_and_explain(df)

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    result = {"timestamp": timestamp, "results": ranked}

    print(json.dumps(result, indent=2))

    with open("screener_results.json", "w") as f:

        json.dump(result, f, indent=2)



def market_hours():

    """Return True if time is between 9:30–15:30 EST"""

    now = datetime.now().time()

    return dtime(6, 30) <= now <= dtime(15, 30)



def main():

    print("🚀 Starting Yahoo Screener Agent")

    while True:

        if market_hours():

            run_screener()

            time.sleep(60 * 10)  # every 10 minutes

        else:

            print("⏸ Outside market hours, sleeping...")

            time.sleep(60 * 60)  # check hourly



if __name__ == "__main__":

    main()



"""



✅ How it works





Runs every 10 minutes between 9:30 AM and 3:30 PM.
Fetches data via Yahoo Finance API (using yfinance).
Filters by basic criteria.
Ranks tickers and saves results to screener_results.json.






🔧 Install dependencies





Run in Command Prompt or PowerShell:

pip install yfinance pandas

Then test manually:

python yahoo_screener_agent.py









🕒 2. Auto-run at 9:30 AM and stop at 3:30 PM







Option A — Use

Windows Task Scheduler





Open Task Scheduler → Create Basic Task → “Stock Screener Start”
Trigger: Daily at 9:30 AM
Action: Start a program
Program: python
Arguments: C:\path\to\yahoo_screener_agent.py

Finish.
"""