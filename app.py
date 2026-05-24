import streamlit as st
import requests
import json

st.set_page_config(page_title="Text2SQL", page_icon="🔍", layout="wide")

# Configuration
AWS_API_URL = "https://hipwbthncg.execute-api.us-east-1.amazonaws.com/dev/query"
LOCAL_API_URL = "http://localhost:8000/query"

st.title("Self-Correcting Multi-Agent Text2SQL")

with st.sidebar:
    st.header("Configuration")
    mode = st.radio("Backend", ["Local (FastAPI)", "AWS (API Gateway)"])
    if mode == "Local (FastAPI)":
        API_URL = st.text_input("API URL", value=LOCAL_API_URL)
        payload_key = "user_query"
    else:
        API_URL = st.text_input("API URL", value=AWS_API_URL)
        payload_key = "user_query"

    st.divider()
    st.caption("Prerequisites (Local):")
    st.code("docker compose up -d\n./mvnw spring-boot:run\nuvicorn api_server:app --port 8000", language="bash")

query = st.text_input("Ask a question about your data:", placeholder="e.g. Show me total sales by country")

if st.button("Run Query", type="primary", disabled=not query):
    with st.spinner("Agents are working..."):
        try:
            payload = {payload_key: query}
            response = requests.post(API_URL, json=payload, timeout=60)

            if response.status_code == 200:
                result = response.json()

                col1, col2, col3 = st.columns(3)
                with col1:
                    st.metric("Confidence", f"{result.get('confidence', 0):.0%}")
                with col2:
                    st.metric("Attempts", result.get("attempts", 1))
                with col3:
                    st.metric("Pipeline Time", f"{result.get('pipeline_time_ms', 0)}ms")

                st.subheader("Generated SQL")
                st.code(result.get("sql", result.get("generated_sql", "")), language="sql")

                explanation = result.get("explanation", {})
                if isinstance(explanation, dict) and explanation.get("explanation"):
                    st.subheader("Explanation")
                    st.info(explanation["explanation"])
                    if explanation.get("key_operations"):
                        st.caption("Key operations: " + ", ".join(explanation["key_operations"]))

                execution_result = result.get("execution_result", {})
                if isinstance(execution_result, dict) and execution_result.get("rows"):
                    st.subheader("Results")
                    columns = execution_result.get("columns", [])
                    rows = execution_result.get("rows", [])
                    if columns and rows:
                        import pandas as pd
                        df = pd.DataFrame(rows, columns=columns)
                        st.dataframe(df, use_container_width=True)
                    else:
                        st.json(rows)
                    st.caption(f"{execution_result.get('rowCount', len(rows))} rows returned in {execution_result.get('executionTimeMs', 0)}ms")
                elif isinstance(execution_result, dict) and execution_result.get("error"):
                    st.error(f"Execution failed: {execution_result['error'].get('message', 'Unknown error')}")

                with st.expander("Full Pipeline Response"):
                    st.json(result)
            else:
                st.error(f"Error {response.status_code}")
                try:
                    detail = response.json().get("detail", response.text)
                    st.code(detail)
                except Exception:
                    st.code(response.text)

        except requests.ConnectionError:
            st.error(f"Cannot connect to {API_URL}. Make sure the backend is running.")
        except requests.Timeout:
            st.error("Request timed out (60s). The LLM might be slow or the server is unreachable.")
        except Exception as e:
            st.error(f"Unexpected error: {e}")
